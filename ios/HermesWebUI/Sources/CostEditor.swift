import SwiftUI

struct CostEditorView: View {
    @ObservedObject var store: AppStore
    @State private var plans: [CostPlan] = []
    @State private var rates: [CostModelRate] = []
    @State private var newName = ""
    @State private var newPrice = ""
    @State private var newCovers = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("YOUR PLANS").font(.caption.weight(.bold)).foregroundColor(Palette.accent)
            Text("What you actually pay. Console then compares that to estimated API pricing.")
                .font(.caption).foregroundColor(Palette.muted)
            ForEach($plans) { $p in
                VStack(alignment: .leading, spacing: 6) {
                    TextField("Plan name", text: $p.name)
                    HStack {
                        TextField("USD", value: $p.price_usd, format: .number).keyboardType(.decimalPad)
                        Button(p.cycle == "yearly" ? "yearly" : "monthly") {
                            p.cycle = p.cycle == "yearly" ? "monthly" : "yearly"
                        }
                    }
                    TextField("Covers providers (comma)", text: Binding(
                        get: { p.covers_providers.joined(separator: ", ") },
                        set: { p.covers_providers = $0.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty } }
                    ))
                }
                .padding(10).background(Palette.surface).cornerRadius(10)
            }
            TextField("New plan", text: $newName)
            TextField("USD / month", text: $newPrice).keyboardType(.decimalPad)
            TextField("providers (xai-oauth, anthropic…)", text: $newCovers)
            HStack {
                Button("+ Add") {
                    let n = newName.trimmingCharacters(in: .whitespaces)
                    guard !n.isEmpty else { return }
                    plans.append(CostPlan(
                        name: n,
                        price_usd: Double(newPrice) ?? 0,
                        covers_providers: newCovers.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
                    ))
                    newName = ""; newPrice = ""; newCovers = ""
                }
                Button("Save plans") { Task { await store.saveCostPlans(plans) } }
            }.foregroundColor(Palette.accent)

            Text("MODEL API RATES").font(.caption.weight(.bold)).foregroundColor(Palette.accent).padding(.top, 8)
            Text("USD per 1M tokens — estimated public API cost.").font(.caption).foregroundColor(Palette.muted)
            ForEach($rates) { $m in
                VStack(alignment: .leading, spacing: 4) {
                    Text(m.id).foregroundColor(Palette.text)
                    HStack {
                        TextField("in", value: $m.input, format: .number).keyboardType(.decimalPad)
                        TextField("out", value: $m.output, format: .number).keyboardType(.decimalPad)
                        TextField("cache", value: $m.cache_read, format: .number).keyboardType(.decimalPad)
                    }
                }
                .padding(10).background(Palette.surface).cornerRadius(10)
            }
            Button("Save rates") { Task { await store.saveCostRates(rates) } }.foregroundColor(Palette.accent)
        }
        .onAppear { plans = store.costConfig.subscriptions; rates = store.costConfig.models }
        .onChange(of: store.costConfig.models.count) { _ in rates = store.costConfig.models }
        .onChange(of: store.costConfig.subscriptions.count) { _ in plans = store.costConfig.subscriptions }
    }
}
