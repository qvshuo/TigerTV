import java.nio.file.Files;
import java.nio.file.Path;
import art.anjing.tigertv.core.*;

/** Offline entry point invoking the shared Kotlin rules. Does not download media. */
class FilterPlaylist {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("usage: <playlist-url> <input.m3u8> <output.m3u8>");
        String input = Files.readString(Path.of(args[1]));
        HlsFilterResult result = SmartHlsAdsKt.filterSmartHlsAds(input, args[0]);
        Files.writeString(Path.of(args[2]), result == null ? input : result.getPlaylist());
        System.out.println(result == null ? "unchanged" : result.getRemoved());
    }
}
