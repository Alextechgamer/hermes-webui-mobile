import Foundation

struct ConsoleUsage: Codable {
    var connected: Bool = false
    var db_path: String = ""
    var notes: [String] = []
    var totals: ConsoleTotals = .init()
    var burn: ConsoleBurn = .init()
    var models: [ConsoleModel] = []
    var feed: [ConsoleFeed] = []
    var inflight: [ConsoleInflight] = []
    var inflight_basis: String = ""
    var subscriptions: ConsoleSubs = .init()

    enum CodingKeys: String, CodingKey {
        case connected, db_path, notes, totals, burn, models, feed, inflight, inflight_basis, subscriptions
    }

    init() {}
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        connected = (try? c.decode(Bool.self, forKey: .connected)) ?? false
        db_path = (try? c.decode(String.self, forKey: .db_path)) ?? ""
        notes = (try? c.decode([String].self, forKey: .notes)) ?? []
        totals = (try? c.decode(ConsoleTotals.self, forKey: .totals)) ?? .init()
        burn = (try? c.decode(ConsoleBurn.self, forKey: .burn)) ?? .init()
        models = (try? c.decode([ConsoleModel].self, forKey: .models)) ?? []
        feed = (try? c.decode([ConsoleFeed].self, forKey: .feed)) ?? []
        inflight = (try? c.decode([ConsoleInflight].self, forKey: .inflight)) ?? []
        inflight_basis = (try? c.decode(String.self, forKey: .inflight_basis)) ?? ""
        subscriptions = (try? c.decode(ConsoleSubs.self, forKey: .subscriptions)) ?? .init()
    }
}

struct ConsoleTotals: Codable {
    var requests: Int64 = 0
    var tokens_total: Int64 = 0
    var est_cost: Double = 0
    var sessions: Int64 = 0
}

struct ConsoleBurn: Codable {
    var mtd_spend: Double = 0
    var today_spend: Double = 0
    var avg_daily_pace: Double = 0
    var projected_eom: Double = 0
    var day_of_month: Int = 0
    var days_in_month: Int = 0
    var month_label: String = ""
}

struct ConsoleModel: Codable, Identifiable {
    var model: String = ""
    var provider: String = ""
    var requests: Int64 = 0
    var tokens: Int64 = 0
    var share_pct: Double = 0
    var id: String { model + provider }
}

struct ConsoleFeed: Codable, Identifiable {
    var session_short: String = ""
    var source: String = ""
    var model: String = ""
    var task: String = ""
    var tokens_total: Int64 = 0
    var cache_pct: Int = 0
    var est_cost: Double = 0
    var age_s: Double = 0
    var live: Bool = false
    var prompt_in: Int64 = 0
    var output: Int64 = 0
    var calls: Int64 = 0
    var id: String { session_short + model + task + String(age_s) }
}

struct ConsoleInflight: Codable, Identifiable {
    var source: String = ""
    var session_short: String = ""
    var model: String? = nil
    var task: String? = nil
    var age_s: Double = 0
    var prompt_in: Int64 = 0
    var output: Int64 = 0
    var calls: Int64 = 0
    var cache_pct: Int = 0
    var id: String { session_short + (model ?? "") + source }
}

struct ConsoleSubs: Codable {
    var items: [ConsolePlan] = []
    var total_flat_monthly: Double = 0
    var payg_metered_mtd: Double = 0
    var actual_out_of_pocket_month: Double = 0
    var all_metered_equivalent_mtd: Double = 0
    var month_label: String = ""
}

struct ConsolePlan: Codable, Identifiable {
    var name: String = ""
    var monthly_usd: Double = 0
    var metered_mtd: Double = 0
    var breakeven_pct: Double? = nil
    var id: String { name }
}

enum ConsoleFmt {
    static func money(_ v: Double, digits: Int = 2) -> String {
        String(format: "$%.\(digits)f", locale: Locale(identifier: "en_US_POSIX"), v)
    }
    static func tok(_ n: Int64) -> String {
        if n >= 1_000_000_000 { return String(format: "%.1fB", Double(n) / 1_000_000_000) }
        if n >= 1_000_000 { return String(format: "%.1fM", Double(n) / 1_000_000) }
        if n >= 1_000 { return String(format: "%.1fK", Double(n) / 1_000) }
        return "\(n)"
    }
    static func int(_ n: Int64) -> String { "\(n)" }
    static func age(_ s: Double) -> String {
        if s < 90 { return "just now" }
        if s < 3600 { return "\(Int(s / 60))m ago" }
        if s < 86400 { return "\(Int(s / 3600))h ago" }
        return "\(Int(s / 86400))d ago"
    }
    static func consoleBase(webui: String, override: String) -> String {
        let o = override.trimmingCharacters(in: .whitespacesAndNewlines)
        if !o.isEmpty { return o.hasSuffix("/") ? String(o.dropLast()) : o }
        var s = webui.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.hasSuffix("/") { s = String(s.dropLast()) }
        if let r = s.range(of: ":\\d+$", options: .regularExpression) {
            return s.replacingCharacters(in: r, with: ":8790")
        }
        return s.isEmpty ? "" : s + ":8790"
    }
}
