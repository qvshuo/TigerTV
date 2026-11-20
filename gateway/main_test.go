package main

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

const sourcesFixture = `{"api_site":{
	"z":{"name":"🎬 first","api":"https://catalog.test/api?ac=list&keep=1"},
	"a":{"name":"🎬 second","api":"https://second.test/api"},
	"duplicate":{"name":"🎬 duplicate","api":"https://second.test/api"},
	"proxy":{"name":"🎬 proxy","api":"https://third.test/api?url=x"},
	"comment":{"name":"🎬 comment","api":"https://comment.test/api","_comment":null},
	"local":{"name":"🎬 local","api":"https://127.0.0.1/api"},
	"plain":{"name":"🎬 plain","api":"http://plain.test/api"}
}}`

type roundTripFunc func(*http.Request) (*http.Response, error)

func (f roundTripFunc) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

func newTestGateway(t *testing.T) *gateway {
	t.Helper()
	sites, config, err := loadSources([]byte(sourcesFixture), "")
	if err != nil {
		t.Fatal(err)
	}
	return &gateway{sites: sites, config: config, origin: "https://web.example", webDir: t.TempDir(), client: metadataClient(), slots: make(chan struct{}, 20)}
}

func request(g *gateway, method, route, origin string) *httptest.ResponseRecorder {
	r := httptest.NewRequest(method, route, nil)
	r.Header.Set("Origin", origin)
	w := httptest.NewRecorder()
	g.ServeHTTP(w, r)
	return w
}

func TestAllowedRoutes(t *testing.T) {
	g := newTestGateway(t)
	g.client.Transport = roundTripFunc(func(r *http.Request) (*http.Response, error) {
		t.Fatalf("unexpected upstream request: %s", r.URL)
		return nil, nil
	})
	if err := os.WriteFile(filepath.Join(g.webDir, "index.html"), []byte("TigerTV"), 0644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(g.webDir, "key.bin"), []byte("media"), 0644); err != nil {
		t.Fatal(err)
	}
	tests := []struct {
		method, route string
		status        int
	}{
		{"GET", "/", 200}, {"GET", prefix + "config", 200}, {"POST", prefix + "config", 405},
		{"GET", prefix + "detail/unknown?id=1", 404}, {"GET", prefix + "search/proxy?q=show", 404},
		{"GET", prefix + "search/z?q=show&url=https://example.test/stream.m3u8", 400},
		{"GET", prefix + "search/z?q=show&page=101", 400}, {"GET", prefix + "search/z?q=show&page=", 400},
		{"GET", prefix + "detail/z?id=1&id=2", 400}, {"GET", prefix + "detail/z?id=2147483648", 400},
		{"GET", prefix + "hot/tv_domestic?target=https://example.test", 404},
		{"GET", "/master.m3u8", 404}, {"GET", "/assets/part.m4s", 404}, {"GET", "/key.bin", 404},
		{"GET", "/assets/../index.html", 404},
	}
	for _, test := range tests {
		w := request(g, test.method, test.route, "")
		if w.Code != test.status {
			t.Errorf("%s %s: got %d want %d", test.method, test.route, w.Code, test.status)
		}
	}
	config := request(g, "GET", prefix+"config", g.origin)
	if config.Header().Get("Access-Control-Allow-Origin") != g.origin ||
		!bytes.HasPrefix(config.Body.Bytes(), []byte(`{"api_site":{"z":`)) || len(g.sites) != 2 {
		t.Fatalf("unexpected config %s", config.Body.String())
	}
	if request(g, "GET", prefix+"config", "https://other.test").Header().Get("Access-Control-Allow-Origin") != "" {
		t.Fatal("unconfigured Origin allowed")
	}
	sites, _, err := loadSources([]byte(sourcesFixture), " a,other ")
	if err != nil || len(sites) != 1 || sites["a"].Gateway != "a" {
		t.Fatal("unexpected site subset")
	}
}

func TestProjections(t *testing.T) {
	g := newTestGateway(t)
	g.client.Transport = roundTripFunc(func(r *http.Request) (*http.Response, error) {
		if r.Header.Get("User-Agent") != userAgent {
			t.Fatal("missing upstream User-Agent")
		}
		var body string
		if r.URL.Host == "m.douban.com" {
			if r.URL.Path != "/rexxar/api/v2/subject_collection/tv_domestic/items" || r.Header.Get("Referer") != "https://m.douban.com/tv/" {
				t.Fatalf("incorrect recommendation request: %s", r.URL)
			}
			body = `{"subject_collection_items":[{"title":"片","pic":{"large":"https://image.test/poster","other":1},"rating":{"value":8}}]}`
		} else {
			q := r.URL.Query()
			if r.URL.Host != "catalog.test" || q.Get("keep") != "1" || q.Get("ac") != "detail" ||
				(q.Has("wd") && (q.Get("wd") != "逐 玉" || q.Get("pg") != "2" || q.Get("pagesize") != "100")) ||
				(!q.Has("wd") && q.Get("ids") != "7") {
				t.Fatalf("incorrect catalog request: %s", r.URL)
			}
			body = `{"code":1.0,"pagecount":2,"list":[{"vod_id":7,"vod_name":"片","vod_play_url":"第1集$https://cdn.test/movie.m3u8","ignored":"x"}]}`
		}
		return &http.Response{StatusCode: 200, Body: io.NopCloser(strings.NewReader(body))}, nil
	})
	for _, tc := range []struct{ route, want string }{
		{"search/z?q=%E9%80%90+%E7%8E%89&page=2", `{"code":"1.0","list":[{"vod_id":"7","vod_name":"片"}],"pagecount":"2"}`},
		{"detail/z?id=7", `{"code":"1.0","list":[{"vod_id":"7","vod_name":"片","vod_play_url":"第1集$https://cdn.test/movie.m3u8"}],"pagecount":"2"}`},
		{"hot/tv_domestic", `{"subject_collection_items":[{"title":"片","pic":{"large":"https://image.test/poster"},"rating":{"value":8}}]}`},
	} {
		w := request(g, "GET", prefix+tc.route, "")
		if w.Code != 200 || strings.TrimSpace(w.Body.String()) != tc.want {
			t.Errorf("%s: %d %s", tc.route, w.Code, w.Body.String())
		}
	}
}

func TestRejectsUpstreamFailuresAndBoundsWork(t *testing.T) {
	g := newTestGateway(t)
	redirect := func(r *http.Request) (*http.Response, error) {
		if r.URL.Host != "catalog.test" {
			t.Fatalf("followed redirect to %s", r.URL)
		}
		return &http.Response{StatusCode: 302, Header: http.Header{"Location": {"https://cdn.test/master.m3u8"}}, Body: http.NoBody}, nil
	}
	g.client.Transport = roundTripFunc(redirect)
	route := prefix + "detail/z?id=7"
	if w := request(g, "GET", route, ""); w.Code != 502 {
		t.Fatalf("redirect status %d", w.Code)
	}
	g.client.Transport = roundTripFunc(func(*http.Request) (*http.Response, error) {
		return &http.Response{StatusCode: 200, Body: io.NopCloser(bytes.NewReader(make([]byte, maxBytes+1)))}, nil
	})
	if w := request(g, "GET", route, ""); w.Code != 502 {
		t.Fatalf("over-limit status %d", w.Code)
	}
	g.client.Transport = roundTripFunc(func(*http.Request) (*http.Response, error) {
		t.Fatal("concurrency cap did not prevent upstream access")
		return nil, nil
	})
	for range cap(g.slots) {
		g.slots <- struct{}{}
	}
	if w := request(g, "GET", route, ""); w.Code != 503 {
		t.Fatalf("busy status %d", w.Code)
	}
	if w := request(g, "GET", prefix+"config", ""); w.Code != 200 || !json.Valid(w.Body.Bytes()) {
		t.Fatal("busy upstream prevented serving config")
	}
}
