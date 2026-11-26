import UIKit
import WebKit

final class WorkspaceDownloads: NSObject, WKDownloadDelegate {
    weak var presenter: UIViewController?
    private let site = WorkspaceSite.configured
    private var files: [ObjectIdentifier: URL] = [:]
    private var ready: [URL] = []

    func download(_ download: WKDownload, decideDestinationUsing response: URLResponse,
                  suggestedFilename: String, completionHandler: @escaping (URL?) -> Void) {
        guard site.allowsDownload(response.url) else { completionHandler(nil); return }
        // Each download owns a private directory. The server supplies only the display name.
        let filename = (suggestedFilename as NSString).lastPathComponent
        let safeName = filename.isEmpty || filename.hasPrefix(".") ? "导出文件" : filename
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false)
            let file = directory.appendingPathComponent(safeName)
            files[ObjectIdentifier(download)] = file
            completionHandler(file)
        } catch { completionHandler(nil) }
    }

    func download(_ download: WKDownload, willPerformHTTPRedirection response: HTTPURLResponse,
                  newRequest request: URLRequest,
                  decisionHandler: @escaping (WKDownload.RedirectPolicy) -> Void) {
        decisionHandler(site.contains(request.url) ? .allow : .cancel)
    }

    func downloadDidFinish(_ download: WKDownload) {
        guard let file = files.removeValue(forKey: ObjectIdentifier(download)) else { return }
        ready.append(file)
        presentNext()
    }

    @objc func didBecomeActive(_ notification: Notification) { presentNext() }

    func presentNext() {
        guard !ready.isEmpty, let presenter, presenter.viewIfLoaded?.window != nil,
              presenter.presentedViewController == nil,
              presenter.view.window?.windowScene?.activationState == .foregroundActive else { return }
        let file = ready.removeFirst()
        let share = UIActivityViewController(activityItems: [file], applicationActivities: nil)
        share.popoverPresentationController?.sourceView = presenter.view
        share.popoverPresentationController?.sourceRect = presenter.view.bounds
        share.completionWithItemsHandler = { [weak self] _, _, _, _ in
            try? FileManager.default.removeItem(at: file.deletingLastPathComponent())
            DispatchQueue.main.async { self?.presentNext() }
        }
        presenter.present(share, animated: true)
    }

    func download(_ download: WKDownload, didFailWithError error: Error, resumeData: Data?) {
        if let file = files.removeValue(forKey: ObjectIdentifier(download)) {
            try? FileManager.default.removeItem(at: file.deletingLastPathComponent())
        }
        if let presenter, presenter.presentedViewController == nil, presenter.viewIfLoaded?.window != nil {
            let alert = UIAlertController(title: "导出失败", message: "请检查网络并重新导出。", preferredStyle: .alert)
            alert.addAction(UIAlertAction(title: "好", style: .default))
            presenter.present(alert, animated: true)
        }
    }
}
