import SwiftUI
import UIKit

@main
struct YangbinAgentApp: App {
    var body: some Scene {
        WindowGroup { WorkspaceHost() }
    }
}

struct WorkspaceHost: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UINavigationController {
        UINavigationController(rootViewController: WorkspaceController())
    }

    func updateUIViewController(_ controller: UINavigationController, context: Context) {}
}
