import SwiftUI
import Shared

@main
struct PodkhodApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeView()
                .ignoresSafeArea()
                .preferredColorScheme(.dark)
        }
    }
}

/// The whole UI is Compose Multiplatform from the :shared module.
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
