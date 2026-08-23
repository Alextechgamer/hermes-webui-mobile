import SwiftUI

/// 1:1 native port of the Hermes Console desktop dashboard (:8790).
/// Same cards, same order, same copy: topbar + KPI strip, Live Usage
/// (scope segment, in-flight streams, request feed), Monthly Burn Rate,
/// Real Cost vs Metered, Usage Overview, Popular Models, notes bar, and
/// the Detailed-usage overlay with the five tables. Auto-refresh every 4s.
struct DashboardPane: View {
    @ObservedObject var store: AppStore
    @State private var auto = true
    @State private var scope = "all"
    @State private var detailOpen = false

    var body: some View {
        let u = store.console
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                topbar(u)
                if let e = store.consoleError { Text(e).font(.footnote).foregroundColor(.red) }
                kpiStrip(u)
                liveUsageCard(u)
                burnCard(u.burn)
                subsCard(u.subscriptions)
                overviewCard(u.totals)
                modelsCard(u.models)
                if !u.notes.isEmpty {
                    VStack(alignment: .leading, spacing: 4) {
                        ForEach(u.notes, id: \.self) { n in
                            Text("ⓘ \(n)").font(.system(size: 11)).foregroundColor(Palette.muted)
                        }
                    }
                }
                CostEditorView(store: store)
            }.padding(16)
        }
        .background(Palette.bg)
        .task(id: auto) {
            while auto && !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 4_000_000_000)
                if !Task.isCancelled { await store.loadConsole() }
            }
        }
        .sheet(isPresented: $detailOpen) { DetailUsageSheet(store: store) }
    }

    // MARK: topbar

    private func topbar(_ u: ConsoleUsage) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    (Text("HERMES ").foregroundColor(Palette.text) + Text("CONSOLE").foregroundColor(Palette.accent))
                        .font(.caption.bold())
                    Text(subline(u)).font(.caption).foregroundColor(Palette.muted)
                }
                Spacer()
                pill(u.connected ? "● Connected" : "● Offline", on: u.connected, danger: !u.connected)
            }
            HStack(spacing: 6) {
                pill(auto ? "⟳ Auto-refresh" : "⏸ Paused", on: auto) { auto.toggle() }
                pill("↻ Refresh") { Task { await store.loadConsole() } }
                pill("▤ Detailed usage") { detailOpen = true }
            }
        }
    }

    private func subline(_ u: ConsoleUsage) -> String {
        var parts: [String] = []
        if let last = u.db_path.split(separator: "/").last, !last.isEmpty { parts.append(String(last)) }
        if u.totals.sessions > 0 { parts.append("\(u.totals.sessions) sessions") }
        return parts.isEmpty ? "usage desk :8790" : parts.joined(separator: " · ")
    }

    // MARK: KPI strip

    private func kpiStrip(_ u: ConsoleUsage) -> some View {
        VStack(spacing: 8) {
            HStack(spacing: 8) {
                kpi("REQUESTS MTD", ConsoleFmt.int(u.totals.requests))
                kpi("TOKENS", ConsoleFmt.tok(u.totals.tokens_total))
            }
            HStack(spacing: 8) {
                kpi("EST. BURN MTD", ConsoleFmt.money(u.burn.mtd_spend), gold: true)
                kpi("OUT-OF-POCKET", ConsoleFmt.money(u.subscriptions.actual_out_of_pocket_month))
            }
        }
    }

    // MARK: Live Usage (streams + request feed)

    private func liveUsageCard(_ u: ConsoleUsage) -> some View {
        card {
            HStack {
                Text("Live Usage").foregroundColor(Palette.text).bold()
                pill("● LIVE", on: true)
                Spacer()
                seg("All sources", sel: scope == "all") { scope = "all" }
                seg("WebUI", sel: scope == "webui") { scope = "webui" }
            }
            Text("IN-FLIGHT STREAMS · \(u.inflight_basis.isEmpty ? "—" : u.inflight_basis.uppercased())")
                .font(.system(size: 10)).foregroundColor(Palette.muted)
            if u.inflight.isEmpty {
                VStack(alignment: .leading, spacing: 2) {
                    Text("No active stream").foregroundColor(Palette.text).fontWeight(.semibold)
                    Text("idle. nothing in flight right now").font(.system(size: 11)).foregroundColor(Palette.muted)
                }
            } else {
                ForEach(u.inflight) { row in
                    streamRow(row, u: u)
                }
            }
            let rows = feedRows(u)
            if rows.isEmpty {
                Text("No rows for this scope.").font(.footnote).foregroundColor(Palette.muted).padding(.top, 8)
            } else {
                ForEach(rows) { r in feedRow(r) }
            }
        }
    }

    private func feedRows(_ u: ConsoleUsage) -> [ConsoleFeed] {
        var rows = u.feed
        if scope == "webui" { rows = rows.filter { $0.source == "webui" } }
        return Array(rows.prefix(40))
    }

    private func streamRow(_ row: ConsoleInflight, u: ConsoleUsage) -> some View {
        let feed = u.feed.first { $0.session_short == row.session_short && $0.live }
            ?? u.feed.first { $0.session_short == row.session_short }
        let model = row.model ?? feed?.model ?? "model"
        let task = row.task ?? feed?.task ?? "chat"
        let provider = row.provider ?? feed?.provider ?? ""
        let elapsed = row.age_s < 1 ? "<1s" : "\(Int(row.age_s))s"
        return VStack(alignment: .leading, spacing: 6) {
            HStack {
                VStack(alignment: .leading, spacing: 1) {
                    Text(model).foregroundColor(Palette.text).fontWeight(.semibold)
                    Text("\(provider) · \(task)").font(.system(size: 11)).foregroundColor(Palette.muted)
                }
                Spacer()
                Text("LIVE").font(.system(size: 10, weight: .bold)).foregroundColor(Palette.accent)
            }
            HStack(spacing: 6) {
                mini("⏱ LAST TOUCHED", elapsed)
                mini("⚡ REQUESTS", row.calls > 0 ? ConsoleFmt.int(row.calls) : (feed.map { ConsoleFmt.int($0.calls) } ?? "—"))
            }
            HStack(spacing: 6) {
                mini("◆ PROMPT TOKENS", ConsoleFmt.tok(row.prompt_in > 0 ? row.prompt_in : (feed?.prompt_in ?? 0)))
                mini("▣ OUTPUT TOKENS", ConsoleFmt.tok(row.output > 0 ? row.output : (feed?.output ?? 0)))
            }
            Text("\(row.session_short.isEmpty ? (feed?.session_short ?? "") : row.session_short) · \(row.source.isEmpty ? "lease" : row.source) · cache \(feed?.cache_pct ?? row.cache_pct)%")
                .font(.system(size: 11)).foregroundColor(Palette.muted)
        }.padding(.bottom, 6)
    }

    private func feedRow(_ r: ConsoleFeed) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack(spacing: 6) {
                Text("200")
                    .font(.system(size: 10, design: .monospaced)).foregroundColor(Palette.accent)
                    .padding(.horizontal, 6).padding(.vertical, 2)
                    .background(Palette.accent.opacity(0.08)).cornerRadius(5)
                Text(r.session_short).font(.system(size: 11, design: .monospaced)).foregroundColor(Palette.muted)
                Text(r.source.isEmpty ? "—" : r.source).font(.system(size: 12, weight: .semibold)).foregroundColor(Palette.text)
                Spacer()
                Text(r.est_cost > 0 ? ConsoleFmt.money(r.est_cost, digits: 4) : "$0.0000")
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundColor(r.est_cost > 0 ? Palette.accent : Palette.muted)
                costBadge(r.cost_status)
            }
            Text((r.title.isEmpty ? "" : "\(String(r.title.prefix(42))) · ") + "\(r.model) · \(r.task) · \(ConsoleFmt.tok(r.tokens_total)) tok · \(ConsoleFmt.age(r.age_s))")
                .font(.system(size: 11)).foregroundColor(Palette.muted)
            Text("tokens in \(ConsoleFmt.tok(r.prompt_in)) · cached \(ConsoleFmt.tok(r.cache_read)) · non-cached \(ConsoleFmt.tok(max(0, r.input))) · out \(ConsoleFmt.tok(r.output))"
                 + (r.reasoning > 0 ? " · reason \(ConsoleFmt.tok(r.reasoning))" : "")
                 + " | cache \(r.cache_pct)% | calls \(ConsoleFmt.int(r.calls))"
                 + (r.cost_source.isEmpty ? "" : " | src \(r.cost_source)"))
                .font(.system(size: 10)).foregroundColor(Palette.muted)
        }
        .padding(.vertical, 6)
    }

    // MARK: Monthly Burn Rate

    private func burnCard(_ b: ConsoleBurn) -> some View {
        card {
            Text("Monthly Burn Rate").foregroundColor(Palette.text).bold()
            Text(b.cost_basis == "actual" ? "MTD Spend (actual)" : "MTD Spend (estimated)")
                .font(.system(size: 11)).foregroundColor(Palette.muted)
            Text(ConsoleFmt.money(b.mtd_spend)).font(.largeTitle.monospacedDigit()).foregroundColor(Palette.accent)
            HStack(spacing: 16) {
                VStack(alignment: .leading, spacing: 1) {
                    Text("Today").font(.system(size: 11)).foregroundColor(Palette.muted)
                    Text(ConsoleFmt.money(b.today_spend)).font(.body.monospacedDigit()).foregroundColor(Palette.text)
                }
                VStack(alignment: .leading, spacing: 1) {
                    Text("Average daily pace").font(.system(size: 11)).foregroundColor(Palette.muted)
                    Text(ConsoleFmt.money(b.avg_daily_pace)).font(.body.monospacedDigit()).foregroundColor(Palette.text)
                }
            }
            Text("Projected EOM").font(.system(size: 11)).foregroundColor(Palette.muted).padding(.top, 6)
            HStack(spacing: 4) {
                Text(ConsoleFmt.money(b.projected_eom)).font(.title3.monospacedDigit()).foregroundColor(Palette.text)
                Text("↗").foregroundColor(Palette.accent)
            }
            Text(b.monthly_cap.map { "Cap \(ConsoleFmt.money($0))" } ?? "No monthly cap set")
                .font(.system(size: 11)).foregroundColor(Palette.muted)
            let frac = b.days_in_month > 0 ? CGFloat(b.day_of_month) / CGFloat(b.days_in_month) : 0
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    RoundedRectangle(cornerRadius: 3).fill(Palette.border).frame(height: 6)
                    RoundedRectangle(cornerRadius: 3).fill(Palette.accent)
                        .frame(width: geo.size.width * min(max(frac, 0), 1), height: 6)
                }
            }.frame(height: 6).padding(.top, 4)
            HStack {
                Text("Day \(b.day_of_month) of \(b.days_in_month) · \(b.month_label.isEmpty ? "this month" : b.month_label)")
                    .font(.system(size: 11)).foregroundColor(Palette.muted)
                Spacer()
                Text(b.cost_basis).font(.system(size: 10)).foregroundColor(Palette.accent)
            }
        }
    }

    // MARK: Real Cost vs Metered

    private func subsCard(_ s: ConsoleSubs) -> some View {
        card {
            Text("Real Cost vs Metered").foregroundColor(Palette.text).bold()
            Text("Actual out-of-pocket · \(s.month_label.isEmpty ? "this month" : s.month_label)")
                .font(.system(size: 11)).foregroundColor(Palette.muted)
            Text(ConsoleFmt.money(s.actual_out_of_pocket_month)).font(.title.monospacedDigit()).foregroundColor(Palette.text)
            HStack(spacing: 16) {
                VStack(alignment: .leading, spacing: 1) {
                    Text("Flat subscriptions").font(.system(size: 11)).foregroundColor(Palette.muted)
                    Text(ConsoleFmt.money(s.total_flat_monthly)).font(.body.monospacedDigit()).foregroundColor(Palette.text)
                }
                VStack(alignment: .leading, spacing: 1) {
                    Text("Pay-as-you-go").font(.system(size: 11)).foregroundColor(Palette.muted)
                    Text(ConsoleFmt.money(s.payg_metered_mtd, digits: s.payg_metered_mtd < 1 ? 4 : 2))
                        .font(.body.monospacedDigit()).foregroundColor(Palette.text)
                }
            }
            Text("Value used (metered-equiv, est.)").font(.system(size: 11)).foregroundColor(Palette.muted).padding(.top, 6)
            HStack(spacing: 4) {
                Text(ConsoleFmt.money(s.all_metered_equivalent_mtd)).font(.title3.monospacedDigit()).foregroundColor(Palette.text)
                Text("MTD").font(.system(size: 11)).foregroundColor(Palette.muted)
            }
            meteredNote(s)
            ForEach(s.items) { plan in planRow(plan) }
            if !s.payg_providers.isEmpty { paygRow(s) }
        }
    }

    private func meteredNote(_ s: ConsoleSubs) -> some View {
        let delta = s.all_metered_equivalent_mtd - s.total_flat_monthly
        let anyPartial = s.items.contains { !$0.all_priced && $0.calls_mtd > 0 }
        let tail = delta >= 0
            ? "\(ConsoleFmt.money(delta)) of value beyond what you pay"
            : "\(ConsoleFmt.money(abs(delta))) under breakeven"
        return Text("You pay \(ConsoleFmt.money(s.total_flat_monthly)) flat; same usage metered ≈ \(ConsoleFmt.money(s.all_metered_equivalent_mtd)) so far. \(tail)\(anyPartial ? " [partial]" : "")")
            .font(.system(size: 11))
            .foregroundColor(delta >= 0 ? .green : .red)
    }

    private func planRow(_ plan: ConsolePlan) -> some View {
        let noData = plan.calls_mtd == 0
        let pctRaw = plan.breakeven_pct ?? 0
        let over = pctRaw > 100
        return VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(plan.name).foregroundColor(Palette.text).fontWeight(.semibold)
                if !plan.note.isEmpty { Text(plan.note).font(.system(size: 10)).foregroundColor(Palette.muted) }
                Spacer()
                Text(ConsoleFmt.money(plan.monthly_usd) + "/mo").font(.body.monospacedDigit()).foregroundColor(Palette.text)
            }
            Text(noData ? "no usage this month"
                 : "\(ConsoleFmt.money(plan.metered_mtd, digits: plan.metered_mtd < 1 ? 4 : 2)) metered-equiv MTD\(plan.all_priced ? "" : " [partial]") · proj EOM \(ConsoleFmt.money(plan.metered_projected_eom)) · \(ConsoleFmt.int(plan.calls_mtd)) calls")
                .font(.system(size: 11)).foregroundColor(Palette.muted)
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    RoundedRectangle(cornerRadius: 3).fill(Palette.border).frame(height: 5)
                    RoundedRectangle(cornerRadius: 3).fill(over ? Color.red : Palette.accent)
                        .frame(width: geo.size.width * min(max(CGFloat(pctRaw / 100), 0), 1), height: 5)
                }
            }.frame(height: 5)
            Text(noData ? "metered at API rates from model_pricing.json"
                 : (plan.savings_vs_metered >= 0
                    ? "+\(ConsoleFmt.money(plan.savings_vs_metered)) value over the \(ConsoleFmt.money(plan.monthly_usd)) plan"
                    : "\(ConsoleFmt.money(abs(plan.savings_vs_metered))) to breakeven")
                 + " · \(Int(pctRaw))% of breakeven"
                 + (plan.all_priced ? "" : " (some usage has no public rate)"))
                .font(.system(size: 11))
                .foregroundColor(noData ? Palette.muted : (plan.savings_vs_metered >= 0 ? .green : .red))
        }.padding(.top, 8)
    }

    private func paygRow(_ s: ConsoleSubs) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text("Pay-as-you-go").foregroundColor(Palette.text).fontWeight(.semibold)
                Spacer()
                Text(ConsoleFmt.money(s.payg_metered_mtd, digits: s.payg_metered_mtd < 1 ? 4 : 2) + " MTD")
                    .font(.body.monospacedDigit()).foregroundColor(Palette.text)
            }
            Text(s.payg_providers.map { "\($0.provider) \(ConsoleFmt.money($0.est, digits: $0.est < 1 ? 4 : 2))" }
                .joined(separator: " · ") + ": actually billed, not on a flat plan")
                .font(.system(size: 11)).foregroundColor(Palette.muted)
        }.padding(.top, 8)
    }

    // MARK: Usage Overview + Popular Models

    private func overviewCard(_ t: ConsoleTotals) -> some View {
        card {
            Text("Usage Overview").foregroundColor(Palette.text).bold()
            Text("\(ConsoleFmt.int(t.requests)) requests").font(.title2).foregroundColor(Palette.text)
            HStack(spacing: 8) {
                mini("Tokens", ConsoleFmt.tok(t.tokens_total))
                mini("Est. Cost", ConsoleFmt.money(t.est_cost), gold: true)
            }
            Button { detailOpen = true } label: {
                Text("Detailed usage →")
                    .font(.footnote.bold()).foregroundColor(Palette.accent)
                    .frame(maxWidth: .infinity)
                    .padding(10)
                    .background(Palette.accent.opacity(0.08))
                    .cornerRadius(8)
            }
        }
    }

    private func modelsCard(_ models: [ConsoleModel]) -> some View {
        card {
            Text("Popular Models").foregroundColor(Palette.text).bold()
            ForEach(Array(models.prefix(6).enumerated()), id: \.element.id) { i, m in
                HStack {
                    Circle().fill(Color(hex: ConsoleFmt.mdot[i % ConsoleFmt.mdot.count])).frame(width: 8, height: 8)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(m.model).font(.system(size: 13, weight: .semibold)).foregroundColor(Palette.text)
                        Text(m.provider).font(.system(size: 11)).foregroundColor(Palette.muted)
                    }
                    Spacer()
                    VStack(alignment: .trailing, spacing: 1) {
                        Text("\(ConsoleFmt.int(m.requests)) req").font(.system(size: 12, design: .monospaced)).foregroundColor(Palette.text)
                        Text("\(String(format: "%.1f", m.share_pct))% · \(ConsoleFmt.tok(m.tokens)) tok").font(.system(size: 11)).foregroundColor(Palette.muted)
                    }
                }.padding(.top, 6)
            }
        }
    }

    // MARK: primitives

    private func pill(_ label: String, on: Bool = false, danger: Bool = false, action: (() -> Void)? = nil) -> some View {
        let fg: Color = danger ? .red : (on ? Palette.accent : Palette.muted)
        let t = Text(label).font(.system(size: 11)).foregroundColor(fg)
            .padding(.horizontal, 10).padding(.vertical, 5)
            .background(on ? Palette.accent.opacity(0.08) : Palette.surface)
            .clipShape(Capsule())
            .overlay(Capsule().stroke(on ? Palette.accent.opacity(0.14) : Palette.border, lineWidth: 1))
        return Group {
            if let action { Button(action: action) { t } } else { t }
        }
    }

    private func seg(_ label: String, sel: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label).font(.system(size: 10))
                .foregroundColor(sel ? Palette.accent : Palette.muted)
                .padding(.horizontal, 8).padding(.vertical, 4)
                .background(sel ? Palette.accent.opacity(0.08) : Palette.bg)
                .cornerRadius(6)
                .overlay(RoundedRectangle(cornerRadius: 6).stroke(sel ? Palette.accent.opacity(0.14) : Palette.border, lineWidth: 1))
        }
    }

    private func costBadge(_ status: String) -> some View {
        let (label, color): (String, Color) = status == "actual" ? ("actual", .green)
            : status == "estimated" ? ("est", Palette.accent)
            : ("no price", Palette.muted)
        return Text(label).font(.system(size: 9)).foregroundColor(color)
            .padding(.horizontal, 4).padding(.vertical, 1)
            .overlay(RoundedRectangle(cornerRadius: 4).stroke(color.opacity(0.4), lineWidth: 1))
    }

    private func kpi(_ label: String, _ value: String, gold: Bool = false) -> some View {
        VStack(alignment: .leading) {
            Text(label).font(.caption2).foregroundColor(Palette.muted)
            Text(value).font(.title3.monospacedDigit()).foregroundColor(gold ? Palette.accent : Palette.text)
        }
        .padding(12).frame(maxWidth: .infinity, alignment: .leading)
        .background(Palette.surface).cornerRadius(12)
    }

    private func mini(_ label: String, _ value: String, gold: Bool = false) -> some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(label).font(.system(size: 9)).foregroundColor(Palette.muted)
            Text(value).font(.system(size: 13, design: .monospaced)).foregroundColor(gold ? Palette.accent : Palette.text)
        }
        .padding(8).frame(maxWidth: .infinity, alignment: .leading)
        .background(Palette.bg).cornerRadius(8)
        .overlay(RoundedRectangle(cornerRadius: 8).stroke(Palette.border, lineWidth: 1))
    }

    private func card<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 6, content: content)
            .padding(14).frame(maxWidth: .infinity, alignment: .leading)
            .background(Palette.surface).cornerRadius(12)
    }
}

/// Detailed-usage overlay: KPI grid + the five desktop tables
/// (By provider, Models, Spend by day, Recent sessions, Full usage buckets).
struct DetailUsageSheet: View {
    @ObservedObject var store: AppStore
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let u = store.console
        let t = u.totals
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    kpiGrid(t)
                    section("By provider")
                    ForEach(u.by_provider) { p in providerRow(p) }
                    section("Models")
                    ForEach(Array(u.models.enumerated()), id: \.element.id) { i, m in modelRow(m, index: i) }
                    section("Spend by day")
                    ForEach(u.spend_by_day) { d in dayRow(d) }
                    section("Recent sessions")
                    ForEach(u.sessions) { s in sessionRow(s) }
                    section("Full usage — every accounting bucket (\(u.feed.count))")
                    ForEach(u.feed.sorted { $0.tokens_total > $1.tokens_total }) { r in bucketRow(r) }
                }.padding(16)
            }
            .background(Palette.bg)
            .navigationTitle("Detailed usage")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }.foregroundColor(Palette.accent)
                }
            }
        }
    }

    private func kpiGrid(_ t: ConsoleTotals) -> some View {
        let pairs: [(String, String)] = [
            ("Requests", ConsoleFmt.int(t.requests)),
            ("Tokens", ConsoleFmt.tok(t.tokens_total)),
            ("Input", ConsoleFmt.tok(t.input_tokens)),
            ("Output", ConsoleFmt.tok(t.output_tokens)),
            ("Cache read", ConsoleFmt.tok(t.cache_read_tokens)),
            ("Cache write", ConsoleFmt.tok(t.cache_write_tokens)),
            ("Reasoning", ConsoleFmt.tok(t.reasoning_tokens)),
            ("Est. cost", ConsoleFmt.money(t.est_cost)),
            ("Sessions", ConsoleFmt.int(t.sessions)),
            ("Buckets", ConsoleFmt.int(t.buckets)),
        ]
        return LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 8) {
            ForEach(pairs, id: \.0) { k, v in
                VStack(alignment: .leading) {
                    Text(k.uppercased()).font(.caption2).foregroundColor(Palette.muted)
                    Text(v).font(.body.monospacedDigit()).foregroundColor(Palette.text)
                }
                .padding(10).frame(maxWidth: .infinity, alignment: .leading)
                .background(Palette.surface).cornerRadius(10)
            }
        }
    }

    private func section(_ title: String) -> some View {
        Text(title.uppercased())
            .font(.system(size: 11, weight: .bold)).foregroundColor(Palette.accent)
            .padding(.top, 6)
    }

    private func providerRow(_ p: ConsoleProvider) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack {
                Text(p.provider).foregroundColor(Palette.text).fontWeight(.semibold)
                Text(p.unpriced ? "no price" : (p.statuses.contains("actual") ? "actual" : "est"))
                    .font(.system(size: 9)).foregroundColor(p.unpriced ? Palette.muted : Palette.accent)
                Spacer()
                Text(ConsoleFmt.money(p.est_cost)).font(.body.monospacedDigit()).foregroundColor(Palette.accent)
            }
            Text("\(ConsoleFmt.int(p.requests)) req · \(ConsoleFmt.tok(p.tokens)) tok")
                .font(.system(size: 11)).foregroundColor(Palette.muted)
        }
        .padding(10).frame(maxWidth: .infinity, alignment: .leading)
        .background(Palette.surface).cornerRadius(10)
    }

    private func modelRow(_ m: ConsoleModel, index: Int) -> some View {
        HStack {
            Circle().fill(Color(hex: ConsoleFmt.mdot[index % ConsoleFmt.mdot.count])).frame(width: 8, height: 8)
            VStack(alignment: .leading, spacing: 1) {
                Text(m.model).font(.system(size: 13, weight: .semibold)).foregroundColor(Palette.text)
                Text(m.provider).font(.system(size: 11)).foregroundColor(Palette.muted)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 1) {
                Text("\(ConsoleFmt.int(m.requests)) req · \(String(format: "%.1f", m.share_pct))%")
                    .font(.system(size: 12, design: .monospaced)).foregroundColor(Palette.text)
                Text("\(ConsoleFmt.tok(m.tokens)) tok · \(ConsoleFmt.money(m.est_cost))")
                    .font(.system(size: 11)).foregroundColor(Palette.muted)
            }
        }
        .padding(10).frame(maxWidth: .infinity, alignment: .leading)
        .background(Palette.surface).cornerRadius(10)
    }

    private func dayRow(_ d: ConsoleDay) -> some View {
        HStack {
            Text(d.date).font(.system(size: 12, design: .monospaced)).foregroundColor(Palette.text)
            Spacer()
            Text("\(ConsoleFmt.int(d.requests)) req").font(.system(size: 12)).foregroundColor(Palette.muted)
            Text(ConsoleFmt.money(d.est_cost)).font(.system(size: 12, design: .monospaced)).foregroundColor(Palette.accent)
        }.padding(.vertical, 2)
    }

    private func sessionRow(_ s: ConsoleSession) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack {
                Text(s.short.isEmpty ? s.sid : s.short).font(.system(size: 11, design: .monospaced)).foregroundColor(Palette.muted)
                Spacer()
                Text(s.act_cost > 0 ? ConsoleFmt.money(s.act_cost) : ConsoleFmt.money(s.est_cost))
                    .font(.system(size: 12, design: .monospaced)).foregroundColor(Palette.accent)
            }
            if !s.title.isEmpty {
                Text(String(s.title.prefix(44))).font(.system(size: 12)).foregroundColor(Palette.text)
            }
            Text(sessionMeta(s)).font(.system(size: 11)).foregroundColor(Palette.muted)
        }
        .padding(10).frame(maxWidth: .infinity, alignment: .leading)
        .background(Palette.surface).cornerRadius(10)
    }

    private func sessionMeta(_ s: ConsoleSession) -> String {
        var parts: [String] = []
        if !s.source.isEmpty { parts.append(s.source) }
        if !s.model.isEmpty { parts.append(s.model) }
        parts.append("\(ConsoleFmt.int(s.messages)) msgs")
        parts.append("\(ConsoleFmt.int(s.calls)) calls")
        if let ts = s.last_activity_at {
            let df = DateFormatter()
            df.dateStyle = .short
            df.timeStyle = .short
            parts.append(df.string(from: Date(timeIntervalSince1970: ts)))
        }
        return parts.joined(separator: " · ")
    }

    private func bucketRow(_ r: ConsoleFeed) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 0) {
                Text(r.session_short).font(.system(size: 11, design: .monospaced)).foregroundColor(Palette.muted)
                Text("  \(r.model) · \(r.task)").font(.system(size: 11)).foregroundColor(Palette.text)
                Text("  calls \(ConsoleFmt.int(r.calls)) · in \(ConsoleFmt.tok(r.input)) · cached \(ConsoleFmt.tok(r.cache_read)) · out \(ConsoleFmt.tok(r.output)) · reason \(r.reasoning > 0 ? ConsoleFmt.tok(r.reasoning) : "—") · cache \(r.cache_pct)% · \(r.est_cost > 0 ? ConsoleFmt.money(r.est_cost, digits: 4) : "$0.0000") \(r.cost_status.isEmpty ? "unknown" : r.cost_status)")
                    .font(.system(size: 11)).foregroundColor(Palette.muted)
            }
        }.padding(.vertical, 2)
    }
}

extension Color {
    /// Init from "#rrggbb" (desktop MDOT palette strings).
    init(hex: String) {
        var s = hex.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.hasPrefix("#") { s = String(s.dropFirst()) }
        var v: UInt64 = 0
        Scanner(string: s).scanHexInt64(&v)
        self.init(
            red: Double((v >> 16) & 0xFF) / 255,
            green: Double((v >> 8) & 0xFF) / 255,
            blue: Double(v & 0xFF) / 255
        )
    }
}
