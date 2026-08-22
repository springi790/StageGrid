import SwiftUI

@main
struct StageGridIOSApp: App {
    @StateObject private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            StartupGateView()
                .environmentObject(model)
                .environmentObject(model.library)
                .environmentObject(model.audio)
                .environmentObject(model.guidePacks)
                .environmentObject(model.midi)
                .environmentObject(model.backup)
                .preferredColorScheme(.dark)
        }
    }
}

private struct StartupGateView: View {
    @EnvironmentObject private var model: AppModel
    @State private var showingSplash = true

    var body: some View {
        ZStack {
            if showingSplash {
                StageGridSplashView()
                    .transition(.opacity)
                    .task {
                        try? await Task.sleep(nanoseconds: 950_000_000)
                        withAnimation(.easeInOut(duration: 0.28)) { showingSplash = false }
                    }
            } else if !model.preferences.setupComplete {
                QuickSetupView { preferences in model.finishSetup(preferences) }
                    .transition(.opacity.combined(with: .scale(scale: 0.98)))
            } else {
                RootView().transition(.opacity)
            }
        }
        .background(Color.sgCanvas.ignoresSafeArea())
    }
}

struct StageGridSplashView: View {
    @State private var pulse = false

    var body: some View {
        ZStack {
            RadialGradient(colors: [Color.sgBlue.opacity(0.22), Color.sgCanvas, Color.black], center: .center, startRadius: 12, endRadius: 520)
                .ignoresSafeArea()
            VStack(spacing: 18) {
                ZStack {
                    RoundedRectangle(cornerRadius: 32, style: .continuous)
                        .fill(Color.sgBlue.opacity(pulse ? 0.28 : 0.12)).frame(width: 126, height: 126).blur(radius: 8)
                    StageGridMark().frame(width: 92, height: 92).scaleEffect(pulse ? 1.04 : 0.96)
                }
                Text("STAGEGRID").font(.system(size: 34, weight: .black, design: .rounded)).tracking(2.2)
                Text("Live Multitrack Workspace").font(.subheadline.weight(.medium)).foregroundStyle(.secondary)
                ProgressView().tint(.sgBlue).padding(.top, 8)
            }
        }
        .onAppear { withAnimation(.easeInOut(duration: 0.72).repeatForever(autoreverses: true)) { pulse = true } }
    }
}

struct StageGridMark: View {
    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 24, style: .continuous).fill(Color.sgBlue)
            HStack(alignment: .center, spacing: 5) {
                ForEach([22.0, 48.0, 70.0, 42.0, 28.0], id: \.self) { height in
                    RoundedRectangle(cornerRadius: 3).fill(.white).frame(width: 8, height: height)
                }
            }
        }
    }
}

struct QuickSetupView: View {
    @EnvironmentObject private var model: AppModel
    let onFinish: (StagePreferences) -> Void
    @State private var step = 0
    @State private var draft = StagePreferences()

    var body: some View {
        VStack(spacing: 18) {
            HStack {
                VStack(alignment: .leading, spacing: 3) {
                    Text("Configuración rápida").font(.title.bold())
                    Text("Deja StageGrid listo para tocar en menos de un minuto.").foregroundStyle(.secondary)
                }
                Spacer()
                Button("Omitir") {
                    var skipped = model.preferences
                    skipped.setupComplete = true
                    onFinish(skipped)
                }
            }
            ProgressView(value: Double(step + 1), total: 4).tint(.sgBlue)
            Group {
                switch step {
                case 0: setupStage
                case 1: setupClick
                case 2: setupGuide
                default: setupSummary
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            .padding(.top, 8).id(step)
            .transition(.asymmetric(insertion: .move(edge: .trailing).combined(with: .opacity), removal: .move(edge: .leading).combined(with: .opacity)))

            HStack(spacing: 12) {
                if step > 0 { Button("Atrás") { withAnimation(.snappy) { step -= 1 } }.buttonStyle(.bordered) }
                Button(step == 3 ? "Terminar" : "Siguiente") {
                    if step == 3 {
                        draft.setupComplete = true
                        onFinish(draft)
                    } else { withAnimation(.snappy) { step += 1 } }
                }
                .buttonStyle(.borderedProminent).tint(.sgBlue).frame(maxWidth: .infinity)
            }
        }
        .padding(24).background(Color.sgCanvas.ignoresSafeArea())
        .onAppear { draft = model.preferences }
    }

    private var setupStage: some View {
        SetupCard(title: "Escenario", subtitle: "Prioriza estabilidad y controles grandes durante una presentación.") {
            Toggle("Modo Live · mantener pantalla activa", isOn: $draft.liveMode)
            Toggle("Performance Lock", isOn: $draft.performanceLock)
        }
    }

    private var setupClick: some View {
        SetupCard(title: "Click y conteo", subtitle: "Puedes cambiarlo después desde Ajustes o Live.") {
            Toggle("Click activado", isOn: $draft.clickEnabled)
            Picker("Subdivisión", selection: Binding(
                get: { draft.resolvedClickSubdivision },
                set: { draft.clickSubdivision = $0 }
            )) {
                ForEach(StageClickSubdivision.allCases) { Text($0.label).tag($0) }
            }
            Picker("Conteo antes de sección", selection: $draft.countInBars) {
                Text("Sin conteo").tag(0)
                Text("1 compás").tag(1)
                Text("2 compases").tag(2)
            }.pickerStyle(.segmented)
        }
    }

    private var setupGuide: some View {
        SetupCard(title: "Guía", subtitle: "Puedes usar la Guide original o Cue Auto con tu Guide Pack.") {
            Toggle("Guía activada", isOn: $draft.guideEnabled)
            Picker("Fuente", selection: Binding(
                get: { draft.resolvedGuideSource },
                set: { draft.guideSource = $0 }
            )) {
                Text("Guía original").tag(StageGuideSource.original)
                Text("Cue Auto").tag(StageGuideSource.cue)
            }.pickerStyle(.segmented)
            Picker("Idioma de cues", selection: $draft.guideLanguage) {
                Text("Auto").tag("auto")
                Text("Español").tag("es")
                Text("English").tag("en")
                Text("Français").tag("fr")
                Text("Português").tag("pt")
            }
        }
    }

    private var setupSummary: some View {
        SetupCard(title: "Listo", subtitle: "Estas preferencias se pueden modificar en cualquier momento.") {
            Label(draft.liveMode ? "Modo Live activado" : "Modo Live desactivado", systemImage: draft.liveMode ? "checkmark.circle.fill" : "circle")
            Label(draft.clickEnabled ? "Click activado" : "Click desactivado", systemImage: "metronome")
            Label(draft.guideEnabled ? "Guía activada" : "Guía desactivada", systemImage: "waveform")
            Text("Click: \(draft.resolvedClickSubdivision.label) · Conteo: \(draft.countInBars == 0 ? "Off" : "\(draft.countInBars) compás(es)")")
                .foregroundStyle(.secondary)
            Text("Guía: \(draft.resolvedGuideSource == .cue ? "Cue Auto" : "Original")").foregroundStyle(.secondary)
        }
    }
}

private struct SetupCard<Content: View>: View {
    let title: String
    let subtitle: String
    @ViewBuilder let content: Content

    init(title: String, subtitle: String, @ViewBuilder content: () -> Content) {
        self.title = title
        self.subtitle = subtitle
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(title).font(.title2.bold())
            Text(subtitle).foregroundStyle(.secondary)
            Divider()
            content
        }
        .padding(20).frame(maxWidth: 720, alignment: .leading)
        .background(Color.sgSurface, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).stroke(Color.white.opacity(0.08)))
    }
}

extension Color {
    static let sgCanvas = Color(red: 0.035, green: 0.043, blue: 0.063)
    static let sgSurface = Color(red: 0.075, green: 0.086, blue: 0.115)
    static let sgBlue = Color(red: 0.357, green: 0.549, blue: 1.0)
    static let sgMint = Color(red: 0.184, green: 0.749, blue: 0.624)
}
