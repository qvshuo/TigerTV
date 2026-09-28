import Foundation

/// 同一部剧在不同源站各出一条搜索结果时的合并展示单元。
/// 展示字段（封面/标题/进度）来自 `primary`；点击默认走 `primary`，
/// 剧集面板可切换到 `sources` 中任意源。
struct MergedSearchResult: Identifiable, Hashable, Sendable {
    let primary: SearchResult
    let sources: [SearchResult]

    /// 稳定 id：由组内全部源拼接而成，避免依赖易变的 vodName。
    var id: String { sources.map { "\($0.site)-\($0.vodId)" }.joined(separator: "|") }

    /// 合并掉的额外源数量；0 表示单源组（UI 与合并前一致）。
    var extraCount: Int { sources.count - 1 }
}

/// 名称归一化：trim、去内部所有空白、小写、全角字母数字转半角。
/// 用于跨站匹配同一部剧（不同站标点/空格/大小写常不一致）。
func normalizeVodName(_ name: String) -> String {
    var out = String()
    out.reserveCapacity(name.count)
    for scalar in name.trimmingCharacters(in: .whitespacesAndNewlines).unicodeScalars {
        if CharacterSet.whitespacesAndNewlines.contains(scalar) { continue }
        var value = scalar.value
        // 全角 ASCII（Ａ-Ｚａ-ｚ０-９）转半角
        switch value {
        case 0xFF21...0xFF3A: value -= (0xFF21 - 0x41) // Ａ-Ｚ → A-Z
        case 0xFF41...0xFF5A: value -= (0xFF41 - 0x61) // ａ-ｚ → a-z
        case 0xFF10...0xFF19: value -= (0xFF10 - 0x30) // ０-９ → 0-9
        default: break
        }
        if let s = Unicode.Scalar(value) {
            out.append(Character(s).lowercased())
        }
    }
    return out
}

/// 从 vod_time 提取 4 位年份（如 "2026-01-01" → "2026"）；缺失/不合法返回 ""。
func vodYear(_ vodTime: String) -> String {
    let t = vodTime.trimmingCharacters(in: .whitespacesAndNewlines)
    let prefix = String(t.prefix(4))
    guard prefix.count == 4, prefix.allSatisfy({ $0.isNumber }) else { return "" }
    return prefix
}

/// 分组 key：归一化名称 + 年份。年份缺失时退化为纯名称（同名不同年不并，防翻拍误并）。
private func mergeKey(_ result: SearchResult) -> String {
    let year = vodYear(result.vodTime)
    let name = normalizeVodName(result.vodName)
    return year.isEmpty ? name : "\(name)#\(year)"
}

/// 主源选择优先级（全部确定性、可单测）：
/// 1. 有 vod_pic 封面者优先；
/// 2. vod_remarks 非空（有更新进度）优先；
/// 3. vod_time 较新者优先（字符串比较，ISO 前缀可直接比）；
/// 4. 原列表中靠前者优先（稳定兜底）。
private func pickPrimary(_ group: [SearchResult]) -> SearchResult {
    group.max { lhs, rhs in
        let lCover = lhs.vodPic.isEmpty ? 0 : 1
        let rCover = rhs.vodPic.isEmpty ? 0 : 1
        if lCover != rCover { return lCover < rCover }
        let lRemarks = lhs.vodRemarks.isEmpty ? 0 : 1
        let rRemarks = rhs.vodRemarks.isEmpty ? 0 : 1
        if lRemarks != rRemarks { return lRemarks < rRemarks }
        return lhs.vodTime < rhs.vodTime
    } ?? group[0]
}

/// 按"同一部剧"归并搜索结果。
/// - 组内 sources 保持原始顺序（先返回的站在前）；
/// - 组间按"组内最早出现位置"插入结果流，避免合并把靠前结果挤到后面；
/// - 单源组原样透传，无回归面。
func mergeSearchResults(_ results: [SearchResult]) -> [MergedSearchResult] {
    guard !results.isEmpty else { return [] }
    var order: [String] = []
    var groups: [String: [SearchResult]] = [:]
    for r in results {
        let key = mergeKey(r)
        if groups[key] == nil {
            groups[key] = []
            order.append(key)
        }
        groups[key]?.append(r)
    }
    return order.compactMap { key in
        guard let members = groups[key], !members.isEmpty else { return nil }
        return MergedSearchResult(primary: pickPrimary(members), sources: members)
    }
}
