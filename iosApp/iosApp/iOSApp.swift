import SwiftUI
import MobileApp

// Minimal SwiftUI shell around the shared Compose Multiplatform UI.
// Create an Xcode iOS App project pointing at this source and link the
// MobileApp.framework produced by :mobile (see README, "Building for iOS").

@main
struct iOSApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeView()
                .ignoresSafeArea(.keyboard)
        }
    }
}

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
