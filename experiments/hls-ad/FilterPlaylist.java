import java.nio.file.Files;
import java.nio.file.Path;
import art.anjing.tigertv.core.*;

/** Offline entry point invoking the shared Kotlin rules. Does not download media. */
class FilterPlaylist {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("usage: <source> <input.m3u8> <output.m3u8>");
        String input = Files.readString(Path.of(args[1]));
        HlsFilterResult result = switch (args[0]) {
            case "🎬暴风资源" -> BaofengAdRuleKt.filterBaofengAds(args[0], input);
            case "🎬-爱奇艺-" -> IqiyiAdRuleKt.filterIqiyiAds(args[0], input);
            case "🎬360 资源" -> ThreeSixtyAdRuleKt.filterThreeSixtyAds(args[0], input);
            case "🎬魔都资源", "🎬魔都动漫" -> ModuAdRuleKt.filterModuAds(args[0], input);
            default -> null;
        };
        Files.writeString(Path.of(args[2]), result == null ? input : result.getPlaylist());
        System.out.println(result == null ? "unchanged" : result.getRemoved());
    }
}
