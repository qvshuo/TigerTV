package main

import (
	"bytes"
	"crypto/tls"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"path"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"
	"unicode/utf16"
)

const (
	prefix    = "/api/metadata/"
	maxBytes  = 10 << 20
	deadline  = 12 * time.Second
	userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/27.2 Safari/605.1.15"
)

var (
	siteName        = regexp.MustCompile(`^[a-zA-Z0-9_.-]+$`)
	positiveDecimal = regexp.MustCompile(`^[1-9][0-9]*$`)
	categories      = map[string]bool{"tv_domestic": true, "tv_variety_show": true, "tv_american": true, "tv_korean": true, "tv_japanese": true, "tv_animation": true}
	cardFields      = []string{"vod_id", "vod_name", "vod_pic", "vod_year", "vod_remarks"}
	detailFields    = append(append([]string{}, cardFields...), "vod_content", "vod_play_from", "vod_play_url")
	contentTypes    = map[string]string{".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8", ".css": "text/css; charset=utf-8", ".webmanifest": "application/manifest+json", ".png": "image/png"}
)

type source struct {
	Name    string `json:"name"`
	API     string `json:"api"`
	Gateway string `json:"gateway"`
}

type gateway struct {
	sites  map[string]source
	config json.RawMessage
	origin string
	webDir string
	client *http.Client
	slots  chan struct{}
}

func loadSources(raw []byte, subset string) (map[string]source, json.RawMessage, error) {
	var config struct {
		Sites json.RawMessage `json:"api_site"`
	}
	if err := json.Unmarshal(raw, &config); err != nil {
		return nil, nil, err
	}
	selected := make(map[string]bool)
	for _, key := range strings.Split(subset, ",") {
		if key = strings.TrimSpace(key); key != "" {
			selected[key] = true
		}
	}

	// Preserve the configured source order when emitting the JSON object.
	sites := make(map[string]source)
	seen := make(map[string]bool)
	decoder := json.NewDecoder(bytes.NewReader(config.Sites))
	if token, err := decoder.Token(); err != nil || token != json.Delim('{') {
		return nil, nil, errors.New("expected api_site object")
	}
	entries := make([][]byte, 0)
	for decoder.More() {
		token, err := decoder.Token()
		if err != nil {
			return nil, nil, err
		}
		key := token.(string)
		var entry map[string]json.RawMessage
		if err := decoder.Decode(&entry); err != nil {
			return nil, nil, err
		}
		if (len(selected) > 0 && !selected[key]) || !siteName.MatchString(key) || entry["_comment"] != nil {
			continue
		}
		var site source
		if json.Unmarshal(entry["name"], &site.Name) != nil || !strings.Contains(site.Name, "🎬") ||
			json.Unmarshal(entry["api"], &site.API) != nil || seen[site.API] || sites[key].API != "" {
			continue
		}
		target, err := url.Parse(site.API)
		if err != nil || target.Scheme != "https" || target.User != nil || target.Fragment != "" ||
			target.Port() != "" || !strings.Contains(target.Hostname(), ".") || net.ParseIP(target.Hostname()) != nil {
			continue
		}
		query, err := url.ParseQuery(target.RawQuery)
		if err != nil || query.Has("url") {
			continue
		}
		site.Gateway = key
		sites[key] = site
		seen[site.API] = true
		name, _ := json.Marshal(key)
		value, _ := json.Marshal(site)
		entries = append(entries, append(append(name, ':'), value...))
	}
	if len(sites) == 0 {
		return nil, nil, errors.New("no HTTPS metadata sources configured")
	}
	return sites, json.RawMessage(append(append([]byte(`{"api_site":{`), bytes.Join(entries, []byte(","))...), '}', '}')), nil
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}

func fail(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, map[string]string{"error": message})
}

func (g *gateway) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("X-Content-Type-Options", "nosniff")
	if r.Method != http.MethodGet {
		fail(w, http.StatusMethodNotAllowed, "GET required")
		return
	}
	if !strings.HasPrefix(r.URL.Path, prefix) {
		g.serveStatic(w, r)
		return
	}
	if g.origin != "" {
		w.Header().Set("Vary", "Origin")
		if r.Header.Get("Origin") == g.origin {
			w.Header().Set("Access-Control-Allow-Origin", g.origin)
		}
	}
	route := strings.TrimPrefix(r.URL.Path, prefix)
	if route == "config" && r.URL.RawQuery == "" {
		writeJSON(w, http.StatusOK, g.config)
		return
	}

	operation, key, found := strings.Cut(route, "/")
	var target *url.URL
	var fields []string
	referer := ""
	switch {
	case found && operation == "hot" && categories[key] && r.URL.RawQuery == "":
		target, _ = url.Parse("https://m.douban.com/rexxar/api/v2/subject_collection/" + key + "/items?start=0&count=12")
		referer = "https://m.douban.com/tv/"
	case found && g.sites[key].API != "" && (operation == "search" || operation == "detail"):
		params, err := url.ParseQuery(r.URL.RawQuery)
		if err != nil {
			fail(w, http.StatusBadRequest, "Invalid query")
			return
		}
		for _, values := range params {
			if len(values) != 1 {
				fail(w, http.StatusBadRequest, "Duplicate parameter")
				return
			}
		}
		target, _ = url.Parse(g.sites[key].API)
		query := target.Query()
		for _, flag := range []string{"ac", "wd", "ids", "pg", "pagesize"} {
			query.Del(flag)
		}
		query.Set("ac", "detail")
		if operation == "search" {
			keyword := strings.TrimSpace(params.Get("q"))
			page := params.Get("page")
			if !params.Has("page") {
				page = "1"
			}
			allowedParams := len(params) == 1 || (len(params) == 2 && params.Has("page"))
			if !allowedParams || keyword == "" || len(utf16.Encode([]rune(keyword))) > 120 || !validNumber(page, 100) {
				fail(w, http.StatusBadRequest, "Invalid search")
				return
			}
			query.Set("wd", keyword)
			query.Set("pg", page)
			query.Set("pagesize", "100")
			fields = cardFields
		} else {
			id := params.Get("id")
			if len(params) != 1 || !validNumber(id, 2147483647) {
				fail(w, http.StatusBadRequest, "Invalid ID")
				return
			}
			query.Set("ids", id)
			fields = detailFields
		}
		target.RawQuery = query.Encode()
	default:
		fail(w, http.StatusNotFound, "Unknown metadata route")
		return
	}

	select {
	case g.slots <- struct{}{}:
		defer func() { <-g.slots }()
	default:
		fail(w, http.StatusServiceUnavailable, "Metadata busy")
		return
	}

	result, err := g.fetch(r, target, referer)
	if err == nil {
		if fields == nil {
			result, err = projectHot(result)
		} else {
			result, err = projectCatalog(result, fields)
		}
	}
	if err != nil {
		if r.Context().Err() == nil {
			fail(w, http.StatusBadGateway, "Upstream metadata unavailable")
		}
		return
	}
	writeJSON(w, http.StatusOK, result)
}

func validNumber(text string, max int) bool {
	if !positiveDecimal.MatchString(text) {
		return false
	}
	value, err := strconv.Atoi(text)
	return err == nil && value <= max
}

func (g *gateway) serveStatic(w http.ResponseWriter, r *http.Request) {
	name := r.URL.Path
	if name == "/" {
		name = "/index.html"
	}
	dir, file := path.Split(name)
	contentType := contentTypes[path.Ext(file)]
	if (dir != "/" && dir != "/assets/") || contentType == "" {
		fail(w, http.StatusNotFound, "Unknown route")
		return
	}
	f, err := os.Open(filepath.Join(g.webDir, name))
	if err != nil {
		fail(w, http.StatusNotFound, "Unknown route")
		return
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil || !info.Mode().IsRegular() {
		fail(w, http.StatusNotFound, "Unknown route")
		return
	}
	w.Header().Set("Content-Type", contentType)
	w.Header().Set("Cross-Origin-Resource-Policy", "same-origin")
	if dir == "/assets/" {
		w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
	} else {
		w.Header().Set("Cache-Control", "no-cache")
	}
	http.ServeContent(w, r, file, info.ModTime(), f)
}

func (g *gateway) fetch(r *http.Request, target *url.URL, referer string) (map[string]json.RawMessage, error) {
	req, err := http.NewRequestWithContext(r.Context(), http.MethodGet, target.String(), nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", userAgent)
	if referer != "" {
		req.Header.Set("Referer", referer)
	}
	response, err := g.client.Do(req)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 || response.ContentLength > maxBytes {
		return nil, errors.New("metadata response rejected")
	}
	body, err := io.ReadAll(io.LimitReader(response.Body, maxBytes+1))
	if err != nil {
		return nil, err
	}
	if len(body) > maxBytes {
		return nil, errors.New("metadata response too large")
	}
	var data map[string]json.RawMessage
	err = json.Unmarshal(body, &data)
	return data, err
}

func numericToken(value json.RawMessage) json.RawMessage {
	if len(value) != 0 && (value[0] == '-' || (value[0] >= '0' && value[0] <= '9')) {
		encoded, _ := json.Marshal(string(value))
		return encoded
	}
	return value
}

func projectCatalog(data map[string]json.RawMessage, fields []string) (map[string]json.RawMessage, error) {
	code := data["code"]
	if code == nil || string(code) == "null" {
		return nil, errors.New("missing catalog code")
	}
	var rows []json.RawMessage
	_ = json.Unmarshal(data["list"], &rows)
	projected := make([]map[string]json.RawMessage, 0, min(len(rows), 100))
	for _, raw := range rows[:min(len(rows), 100)] {
		var row map[string]json.RawMessage
		if json.Unmarshal(raw, &row) != nil || row == nil {
			continue
		}
		out := make(map[string]json.RawMessage)
		for _, field := range fields {
			if value := row[field]; value != nil {
				if field == "vod_id" {
					value = numericToken(value)
				}
				out[field] = value
			}
		}
		projected = append(projected, out)
	}
	list, _ := json.Marshal(projected)
	result := map[string]json.RawMessage{"code": numericToken(code), "list": list}
	if count := data["pagecount"]; count != nil {
		result["pagecount"] = numericToken(count)
	}
	return result, nil
}

type hotItem struct {
	ID      json.RawMessage `json:"id,omitempty"`
	Title   json.RawMessage `json:"title,omitempty"`
	Year    json.RawMessage `json:"year,omitempty"`
	Picture struct {
		Large json.RawMessage `json:"large,omitempty"`
	} `json:"pic"`
	Rating struct {
		Value json.RawMessage `json:"value,omitempty"`
	} `json:"rating"`
}

func projectHot(data map[string]json.RawMessage) (map[string]json.RawMessage, error) {
	var items []hotItem
	if err := json.Unmarshal(data["subject_collection_items"], &items); err != nil || items == nil {
		return nil, errors.New("invalid Douban metadata")
	}
	payload, _ := json.Marshal(items[:min(len(items), 12)])
	return map[string]json.RawMessage{"subject_collection_items": payload}, nil
}

func env(key, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}

func metadataClient() *http.Client {
	transport := http.DefaultTransport.(*http.Transport).Clone()
	transport.Proxy = nil
	transport.DialContext = (&net.Dialer{Timeout: 5 * time.Second, KeepAlive: 30 * time.Second}).DialContext
	transport.TLSClientConfig = &tls.Config{MinVersion: tls.VersionTLS12}
	return &http.Client{
		Transport:     transport,
		Timeout:       deadline,
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}
}

func main() {
	webDir := env("TIGERTV_WEB_DIR", "/app/web")
	raw, err := os.ReadFile(env("TIGERTV_CONFIG", "/app/sources.json"))
	if err != nil {
		log.Fatal(err)
	}
	sites, config, err := loadSources(raw, os.Getenv("TIGERTV_SITES"))
	if err != nil {
		log.Fatal(err)
	}
	server := &http.Server{
		Addr:              net.JoinHostPort(env("TIGERTV_BIND", "0.0.0.0"), env("PORT", "8080")),
		Handler:           &gateway{sites: sites, config: config, origin: os.Getenv("TIGERTV_WEB_ORIGIN"), webDir: webDir, client: metadataClient(), slots: make(chan struct{}, 20)},
		ReadHeaderTimeout: 10 * time.Second, ReadTimeout: 15 * time.Second, WriteTimeout: 15 * time.Second,
		IdleTimeout: 60 * time.Second, MaxHeaderBytes: 8192,
	}
	log.Fatal(server.ListenAndServe())
}
