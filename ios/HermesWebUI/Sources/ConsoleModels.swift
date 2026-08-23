import Foundation

/// Hermes Console `GET /api/usage` (port 8790). Full desktop payload shape;
/// every field decodes leniently so older servers still parse.
struct ConsoleUsage: Codable {
    var connected: Bool = false
    var db_path: String = ""
    var notes: [String] = []
    var totals: ConsoleTotals = .init()
    var burn: ConsoleBurn = .init()
    var by_provider: [ConsoleProvider] = []
    var models: [ConsoleModel] = []
    var feed: [ConsoleFeed] = []
    var spend_by_day: [ConsoleDay] = []
    var inflight: [ConsoleInflight] = []
    var inflight_basis: String = ""
    var subscriptions: ConsoleSubs = .init()
    var sessions: [ConsoleSession] = []

    enum CodingKeys: String, CodingKey {
        case connected, db_path, notes, totals, burn, by_provider, models, feed, spend_by_day, inflight, inflight_basis, subscriptions, sessions
    }

    init() {}
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        connected = (try? c.decode(Bool.self, forKey: .connected)) ?? false
        db_path = (try? c.decode(String.self, forKey: .db_path)) ?? ""
        notes = (try? c.decode([String].self, forKey: .notes)) ?? []
        totals = (try? c.decode(ConsoleTotals.self, forKey: .totals)) ?? .init()
        burn = (try? c.decode(ConsoleBurn.self, forKey: .burn)) ?? .init()
        by_provider = (try? c.decode([ConsoleProvider].self, forKey: .by_provider)) ?? []
        models = (try? c.decode([ConsoleModel].self, forKey: .models)) ?? []
        feed = (try? c.decode([ConsoleFeed].self, forKey: .feed)) ?? []
        spend_by_day = (try? c.decode([ConsoleDay].self, forKey: .spend_by_day)) ?? []
        inflight = (try? c.decode([ConsoleInflight].self, forKey: .inflight)) ?? []
        inflight_basis = (try? c.decode(String.self, forKey: .inflight_basis)) ?? ""
        subscriptions = (try? c.decode(ConsoleSubs.self, forKey: .subscriptions)) ?? .init()
        sessions = (try? c.decode([ConsoleSession].self, forKey: .sessions)) ?? []
    }
}

struct ConsoleTotals: Codable {
    var requests: Int64 = 0
    var buckets: Int64 = 0
    var input_tokens: Int64 = 0
    var output_tokens: Int64 = 0
    var cache_read_tokens: Int64 = 0
    var cache_write_tokens: Int64 = 0
    var reasoning_tokens: Int64 = 0
    var tokens_total: Int64 = 0
    var est_cost: Double = 0
    var act_cost: Double = 0
    var sessions: Int64 = 0
    var cost_basis: String = "estimated"

    enum CodingKeys: String, CodingKey {
        case requests, buckets, input_tokens, output_tokens, cache_read_tokens, cache_write_tokens, reasoning_tokens, tokens_total, est_cost, act_cost, sessions, cost_basis
    }
    init() {}
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        requests = (try? c.decode(Int64.self, forKey: .requests)) ?? 0
        buckets = (try? c.decode(Int64.self, forKey: .buckets)) ?? 0
        input_tokens = (try? c.decode(Int64.self, forKey: .input_tokens)) ?? 0
        output_tokens = (try? c.decode(Int64.self, forKey: .output_tokens)) ?? 0
        cache_read_tokens = (try? c.decode(Int64.self, forKey: .cache_read_tokens)) ?? 0
        cache_write_tokens = (try? c.decode(Int64.self, forKey: .cache_write_tokens)) ?? 0
        reasoning_tokens = (try? c.decode(Int64.self, forKey: .reasoning_tokens)) ?? 0
        tokens_total = (try? c.decode(Int64.self, forKey: .tokens_total)) ?? 0
        est_cost = (try? c.decode(Double.self, forKey: .est_cost)) ?? 0
        act_cost = (try? c.decode(Double.self, forKey: .act_cost)) ?? 0
        sessions = (try? c.decode(Int64.self, forKey: .sessions)) ?? 0
        cost_basis = (try? c.decode(String.self, forKey: .cost_basis)) ?? "estimated"
    }
}

struct ConsoleBurn: Codable {
    var mtd_spend: Double = 0
    var today_spend: Double = 0
    var avg_daily_pace: Double = 0
    var projected_eom: Double = 0
    var day_of_month: Int = 0
    var days_in_month: Int = 0
    var month_label: String = ""
    var monthly_cap: Double? = nil
    var cost_basis: String = "estimated"

    enum CodingKeys: String, CodingKey {
        case mtd_spend, today_spend, avg_daily_pace, projected_eom, day_of_month, days_in_month, month_label, monthly_cap, cost_basis
    }
    init() {}
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        mtd_spend = (try? c.decode(Double.self, forKey: .mtd_spend)) ?? 0
        today_spend = (try? c.decode(Double.self, forKey: .today_spend)) ?? 0
        avg_daily_pace = (try? c.decode(Double.self, forKey: .avg_daily_pace)) ?? 0
        projected_eom = (try? c.decode(Double.self, forKey: .projected_eom)) ?? 0
        day_of_month = (try? c.decode(Int.self, forKey: .day_of_month)) ?? 0
        days_in_month = (try? c.decode(Int.self, forKey: .days_in_month)) ?? 0
        month_label = (try? c.decode(String.self, forKey: .month_label)) ?? ""
        monthly_cap = try? c.decode(Double.self, forKey: .monthly_cap)
        cost_basis = (try? c.decode(String.self, forKey: .cost_basis)) ?? "estimated"
    }
}

struct ConsoleProvider: Codable, Identifiable {
    var provider: String = ""
    var requests: Int64 = 0
    var tokens: Int64 = 0
    var est_cost: Double = 0
    var statuses: [String] = []
    var unpriced: Bool = false
    var id: String { provider }

    enum CodingKeys: String, CodingKey { case provider, requests, tokens, est_cost, statuses, unpriced }
    init() {}
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        provider = (try? c.decode(String.self, forKey: .provider)) ?? ""
        requests = (try? c.decode(Int64.self, forKey: .requests)) ?? 0
        tokens = (try? c.decode(Int64.self, forKey: .tokens)) ?? 0
        est_cost = (try? c.decode(Double.self, forKey: .est_cost)) ?? 0
        statuses = (try? c.decode([String].self, forKey: .statuses)) ?? []
        unpriced = (try? c.decode(Bool.self, forKey: .unpriced)) ?? false
    }
}

struct ConsoleModel: Codable, Identifiable {
    var model: String = ""
    var provider: String = ""
    var requests: Int64 = 0
    var tokens: Int64 = 0
    var est_cost: Double = 0
    var share_pct: Double = 0
    var id: String { model + provider }

    enum CodingKeys: String, CodingKey { case model, provider, requests, tokens, est_cost, share_pct }
    init() {}
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        model = (try? c.decode(String.self, forKey: .model)) ?? ""
        provider = (try? c.decode(String.self, forKey: .provider)) ?? ""
        requests = (try? c.decode(Int64.self, forKey: .requests)) ?? 0
        tokens = (try? c.decode(Int64.self, forKey: .tokens)) ?? 0
        est_cost = (try? c.decode(Double.self, forKey: .est_cost)) ?? 0
        share_pct = (try? c.decode(Double.self, forKey: .share_pct)) ?? 0
    }
}

struct ConsoleDay: Codable, Identifiable {
    var date: String = ""
    var requests: Int64 = 0
    var est_cost: Double = 0
    var id: String { date }

    enum CodingKeys: String, CodingKey { case date, requests, est_cost }
    init() {}
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        date = (try? c.decode(String.self, forKey: .date)) ?? ""
        requests = (try? c.decode(Int64.self, forKey: .requests)) ?? 0
        est_cost = (try? c.decode(Double.self, forKey: .est_cost)) ?? 0
    }
}

struct ConsoleFeed: Codable, Identifiable {
    var session_short: String = ""
    var source: String = ""
    var title: String = ""
    var model: String = ""
    var provider: String = ""
    var task: String = ""
    var calls: Int64 = 0
    var input: Int64 = 0
    var output: Int64 = 0
    var cache_read: Int64 = 0
    var prompt_in: Int64 = 0
    var tokens_total: Int64 = 0
    var cache_pct: Int = 0
    var reasoning: Int64 = 0
    var est_cost: Double = 0
    var cost_status: String = ""
    var cost_source: String = ""
    var age_s: Double = 0
    var live: Bool = false
    var id: String { session_short + model + task + String(age_s) }

    enum CodingKeys: String, CodingKey {
        case session_short, source, title, model, provider, task, calls, input, output, cache_read, prompt_in, tokens_total, cache_pct, reasoning, est_cost, cost_status, cost_source, age_s, live
    }
    init() {}
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        session_short = (try? c.decode(String.self, forKey: .session_short)) ?? ""
        source = (try? c.decode(String.self, forKey: .source)) ?? ""
        title = (try? c.decode(String.self, forKey: .title)) ?? ""
        model = (try? c.decode(String.self, forKey: .model)) ?? ""
        provider = (try? c.decode(String.self, forKey: .provider)) ?? ""
        task = (try? c.decode(String.self, forKey: .task)) ?? ""
        calls = (try? c.decode(Int64.self, forKey: .calls)) ?? 0
        input = (try? c.decode(Int64.self, forKey: .input)) ?? 0
        output = (try? c.decode(Int64.self, forKey: .output)) ?? 0
        cache_read = (try? c.decode(Int64.self, forKey: .cache_read)) ?? 0
        prompt_in = (try? c.decode(Int64.self, forKey: .prompt_in)) ?? 0
        tokens_total = (try? c.decode(Int64.self, forKey: .tokens_total)) ?? 0
        cache_pct = (try? c.decode(Int.self, forKey: .cache_pct)) ?? 0
        reasoning = (try? c.decode(Int64.self, forKey: .reasoning)) ?? 0
        est_cost = (try? c.decode(Double.self, forKey: .est_cost)) ?? 0
        cost_status = (try? c.decode(String.self, forKey: .cost_status)) ?? ""
        cost_source = (try? c.decode(String.self, forKey: .cost_source)) ?? ""
        age_s = (try? c.decode(Double.self, forKey: .age_s)) ?? 0
        live = (try? c.decode(Bool.self, forKey: .live)) ?? false
    }
}

struct ConsoleInflight: Codable, Identifiable {
    var source: String = ""
    var session_short: String = ""
    var model: String? = nil
    var provider: String? = nil
    var task: String? = nil
    var age_s: Double = 0
    var prompt_in: Int64 = 0
    var output: Int64 = 0
    var calls: Int64 = 0
    var cache_pct: Int = 0
    var id: String { session_short + (model ?? "") + source }

    enum CodingKeys: String, CodingKey { case source, session_short, model, provider, task, age_s, prompt_in, output, calls, cache_pct }
    init() {}
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        source = (try? c.decode(String.self, forKey: .source)) ?? ""
        session_short = (try? c.decode(String.self, forKey: .session_short)) ?? ""
        model = try? c.decode(String.self, forKey: .model)
        provider = try? c.decode(String.self, forKey: .provider)
        task = try? c.decode(String.self, forKey: .task)
        age_s = (try? c.decode(Double.self, forKey: .age_s)) ?? 0
        prompt_in = (try? c.decode(Int64.self, forKey: .prompt_in)) ?? 0
        output = (try? c.decode(Int64.self, forKey: .output)) ?? 0
        calls = (try? c.decode(Int64.self, forKey: .calls)) ?? 0
        cache_pct = (try? c.decode(Int.self, forKey: .cache_pct)) ?? 0
    }
}

struct ConsoleSubs: Codable {
    var items: [ConsolePlan] = []
    var total_flat_monthly: Double = 0
    var payg_metered_mtd: Double = 0
    var payg_providers: [ConsolePayg] = []
    var actual_out_of_pocket_month: Double = 0
    var all_metered_equivalent_mtd: Double = 0
    var month_label: String = ""

    enum CodingKeys: String, CodingKey {
        case items, total_flat_monthly, payg_metered_mtd, payg_providers, actual_out_of_pocket_month, all_metered_equivalent_mtd, month_label
    }
    init() {}
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        items = (try? c.decode([ConsolePlan].self, forKey: .items)) ?? []
        total_flat_monthly = (try? c.decode(Double.self, forKey: .total_flat_monthly)) ?? 0
        payg_metered_mtd = (try? c.decode(Double.self, forKey: .payg_metered_mtd)) ?? 0
        payg_providers = (try? c.decode([ConsolePayg].self, forKey: .payg_providers)) ?? []
        actual_out_of_pocket_month = (try? c.decode(Double.self, forKey: .actual_out_of_pocket_month)) ?? 0
        all_metered_equivalent_mtd = (try? c.decode(Double.self, forKey: .all_metered_equivalent_mtd)) ?? 0
        month_label = (try? c.decode(String.self, forKey: .month_label)) ?? ""
    }
}

struct ConsolePayg: Codable, Identifiable {
    var provider: String = ""
    var est: Double = 0
    var calls: Int64 = 0
    var id: String { provider }

    enum CodingKeys: String, CodingKey { case provider, est, calls }
    init() {}
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        provider = (try? c.decode(String.self, forKey: .provider)) ?? ""
        est = (try? c.decode(Double.self, forKey: .est)) ?? 0
        calls = (try? c.decode(Int64.self, forKey: .calls)) ?? 0
    }
}

struct ConsolePlan: Codable, Identifiable {
    var name: String = ""
    var monthly_usd: Double = 0
    var note: String = ""
    var metered_mtd: Double = 0
    var metered_projected_eom: Double = 0
    var calls_mtd: Int64 = 0
    var all_priced: Bool = true
    var savings_vs_metered: Double = 0
    var breakeven_pct: Double? = nil
    var id: String { name }

    enum CodingKeys: String, CodingKey {
        case name, monthly_usd, note, metered_mtd, metered_projected_eom, calls_mtd, all_priced, savings_vs_metered, breakeven_pct
    }
    init() {}
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        name = (try? c.decode(String.self, forKey: .name)) ?? ""
        monthly_usd = (try? c.decode(Double.self, forKey: .monthly_usd)) ?? 0
        note = (try? c.decode(String.self, forKey: .note)) ?? ""
        metered_mtd = (try? c.decode(Double.self, forKey: .metered_mtd)) ?? 0
        metered_projected_eom = (try? c.decode(Double.self, forKey: .metered_projected_eom)) ?? 0
        calls_mtd = (try? c.decode(Int64.self, forKey: .calls_mtd)) ?? 0
        all_priced = (try? c.decode(Bool.self, forKey: .all_priced)) ?? true
        savings_vs_metered = (try? c.decode(Double.self, forKey: .savings_vs_metered)) ?? 0
        breakeven_pct = try? c.decode(Double.self, forKey: .breakeven_pct)
    }
}

struct ConsoleSession: Codable, Identifiable {
    var sid: String = ""
    var short: String = ""
    var source: String = ""
    var model: String = ""
    var provider: String = ""
    var messages: Int64 = 0
    var calls: Int64 = 0
    var est_cost: Double = 0
    var act_cost: Double = 0
    var title: String = ""
    var last_activity_at: Double? = nil
    var id: String { sid.isEmpty ? short : sid }

    enum CodingKeys: String, CodingKey {
        case sid = "id", short, source, model, provider, messages, calls, est_cost, act_cost, title, last_activity_at
    }
    init() {}
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        sid = (try? c.decode(String.self, forKey: .sid)) ?? ""
        short = (try? c.decode(String.self, forKey: .short)) ?? ""
        source = (try? c.decode(String.self, forKey: .source)) ?? ""
        model = (try? c.decode(String.self, forKey: .model)) ?? ""
        provider = (try? c.decode(String.self, forKey: .provider)) ?? ""
        messages = (try? c.decode(Int64.self, forKey: .messages)) ?? 0
        calls = (try? c.decode(Int64.self, forKey: .calls)) ?? 0
        est_cost = (try? c.decode(Double.self, forKey: .est_cost)) ?? 0
        act_cost = (try? c.decode(Double.self, forKey: .act_cost)) ?? 0
        title = (try? c.decode(String.self, forKey: .title)) ?? ""
        last_activity_at = try? c.decode(Double.self, forKey: .last_activity_at)
    }
}

enum ConsoleFmt {
    /// Desktop console model-dot palette (MDOT).
    static let mdot: [String] = ["#ffb020", "#6fb1ff", "#57c98a", "#c792ea", "#ff8f2e", "#7bd88f", "#ec6a9c", "#4bd2c9"]

    static func money(_ v: Double, digits: Int = 2) -> String {
        let f = NumberFormatter()
        f.numberStyle = .decimal
        f.minimumFractionDigits = digits
        f.maximumFractionDigits = digits
        f.locale = Locale(identifier: "en_US_POSIX")
        return "$" + (f.string(from: NSNumber(value: v)) ?? String(format: "%.\(digits)f", v))
    }
    static func tok(_ n: Int64) -> String {
        if n >= 1_000_000_000 { return String(format: "%.1fB", Double(n) / 1_000_000_000) }
        if n >= 1_000_000 { return String(format: "%.1fM", Double(n) / 1_000_000) }
        if n >= 1_000 { return String(format: "%.1fK", Double(n) / 1_000) }
        return "\(n)"
    }
    static func int(_ n: Int64) -> String {
        let f = NumberFormatter()
        f.numberStyle = .decimal
        f.locale = Locale(identifier: "en_US_POSIX")
        return f.string(from: NSNumber(value: n)) ?? "\(n)"
    }
    /// Matches desktop console ago(): <5s just now, then s/m/h/d.
    static func age(_ s: Double) -> String {
        let t = max(0, Int(s))
        if t < 5 { return "just now" }
        if t < 60 { return "\(t)s ago" }
        if t < 3600 { return "\(t / 60)m ago" }
        if t < 86400 { return "\(t / 3600)h ago" }
        return "\(t / 86400)d ago"
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

struct CostConfig: Codable {
    var subscriptions: [CostPlan] = []
    var models: [CostModelRate] = []
    var providers: [String] = []
}

struct CostPlan: Codable, Identifiable, Hashable {
    var name: String = ""
    var price_usd: Double = 0
    var cycle: String = "monthly"
    var note: String = ""
    var covers_providers: [String] = []
    var id: String { name.isEmpty ? UUID().uuidString : name }
}

struct CostModelRate: Codable, Identifiable, Hashable {
    var id: String = ""
    var provider: String = ""
    var requests: Int64 = 0
    var input: Double = 0
    var output: Double = 0
    var cache_read: Double = 0
    var cache_write: Double = 0
    var has_rate: Bool = false
}
