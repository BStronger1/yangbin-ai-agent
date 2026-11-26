import UIKit
import WebKit

final class WorkspaceController: UIViewController, WKNavigationDelegate, WKUIDelegate {
    let site = WorkspaceSite.configured
    private let web = WKWebView()
    private let failure = UIStackView()
    private let explanation = UILabel()
    private let spinner = UIActivityIndicatorView(style: .medium)
    private var retryURL: URL?
    private var backObservation: NSKeyValueObservation?
    private var articleObservation: NSKeyValueObservation?
    private let downloads = WorkspaceDownloads()

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        title = "yangbin AI"
        web.navigationDelegate = self
        web.uiDelegate = self
        web.allowsBackForwardNavigationGestures = true
        downloads.presenter = self
        NotificationCenter.default.addObserver(downloads, selector: #selector(WorkspaceDownloads.didBecomeActive(_:)),
                                               name: UIApplication.didBecomeActiveNotification, object: nil)

        let back = UIBarButtonItem(image: UIImage(systemName: "chevron.backward"), style: .plain,
                                   target: self, action: #selector(goBack))
        back.accessibilityLabel = "返回"
        navigationItem.leftBarButtonItem = back
        backObservation = web.observe(\.canGoBack, options: [.initial, .new]) { view, _ in
            back.isEnabled = view.canGoBack
        }

        let actions = WorkspaceSite.Workspace.allCases.map { space in
            UIAction(title: space.rawValue) { [weak self] _ in
                guard let self else { return }
                self.load(self.site.url(for: space))
            }
        }
        let menu = UIBarButtonItem(image: UIImage(systemName: "square.grid.2x2"), menu: UIMenu(children: actions))
        menu.accessibilityLabel = "切换工作空间"
        let refresh = UIBarButtonItem(barButtonSystemItem: .refresh, target: self, action: #selector(reloadPage))
        refresh.accessibilityLabel = "刷新页面"
        let share = UIBarButtonItem(barButtonSystemItem: .action, target: self, action: #selector(shareArticle(_:)))
        share.accessibilityLabel = "分享公开文章"
        articleObservation = web.observe(\.url, options: [.initial, .new]) { [weak self] view, _ in
            guard let self else { return }
            let publicArticle = self.site.publicArticleURL(view.url)
            self.navigationItem.rightBarButtonItems = publicArticle == nil ? [menu, refresh] : [share, menu, refresh]
        }

        explanation.numberOfLines = 0
        explanation.textAlignment = .center
        explanation.font = .preferredFont(forTextStyle: .body)
        explanation.adjustsFontForContentSizeCategory = true
        let retry = UIButton(type: .system, primaryAction: UIAction(title: "重新连接") { [weak self] _ in
            self?.reloadPage()
        })
        retry.configuration = .tinted()
        retry.setTitle("重新连接", for: .normal)
        failure.axis = .vertical
        failure.spacing = 16
        failure.alignment = .center
        failure.addArrangedSubview(explanation)
        failure.addArrangedSubview(retry)
        failure.isHidden = true

        for item in [web, failure, spinner] as [UIView] {
            item.translatesAutoresizingMaskIntoConstraints = false
            view.addSubview(item)
        }
        let area = view.safeAreaLayoutGuide
        NSLayoutConstraint.activate([
            web.topAnchor.constraint(equalTo: area.topAnchor),
            web.bottomAnchor.constraint(equalTo: area.bottomAnchor),
            web.leadingAnchor.constraint(equalTo: area.leadingAnchor),
            web.trailingAnchor.constraint(equalTo: area.trailingAnchor),
            failure.centerYAnchor.constraint(equalTo: area.centerYAnchor),
            failure.leadingAnchor.constraint(equalTo: area.leadingAnchor, constant: 24),
            failure.trailingAnchor.constraint(equalTo: area.trailingAnchor, constant: -24),
            spinner.centerXAnchor.constraint(equalTo: area.centerXAnchor),
            spinner.centerYAnchor.constraint(equalTo: area.centerYAnchor)
        ])
        load(site.url(for: .coding))
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        downloads.presentNext()
    }

    private func load(_ url: URL) {
        guard site.contains(url) else { return }
        retryURL = url
        web.load(URLRequest(url: url))
    }

    @objc private func goBack() { web.goBack() }

    @objc private func shareArticle(_ sender: UIBarButtonItem) {
        guard presentedViewController == nil, let url = site.publicArticleURL(web.url) else { return }
        let sheet = UIActivityViewController(activityItems: [url], applicationActivities: nil)
        sheet.popoverPresentationController?.barButtonItem = sender
        present(sheet, animated: true)
    }

    @objc private func reloadPage() {
        if !failure.isHidden || web.url == nil {
            load(retryURL ?? site.url(for: .coding))
        } else {
            web.reload()
        }
    }

    private func showFailure(_ message: String) {
        spinner.stopAnimating()
        web.isHidden = true
        explanation.text = message
        failure.isHidden = false
    }

    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let url = action.request.url else { decisionHandler(.cancel); return }
        let sourceIsTrusted = site.contains(action.sourceFrame.request.url)
        if action.shouldPerformDownload && sourceIsTrusted && site.allowsDownload(url) {
            decisionHandler(.download)
        } else if site.contains(url) {
            if action.targetFrame == nil {
                decisionHandler(.cancel)
                webView.load(action.request)
            } else {
                decisionHandler(.allow)
            }
        } else {
            decisionHandler(.cancel)
            if sourceIsTrusted && action.navigationType == .linkActivated &&
                action.targetFrame?.isMainFrame != false && WorkspaceSite.isHTTPS(url) {
                UIApplication.shared.open(url)
            }
        }
    }

    func webView(_ webView: WKWebView, decidePolicyFor response: WKNavigationResponse,
                 decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void) {
        guard site.contains(response.response.url) else { decisionHandler(.cancel); return }
        if let http = response.response as? HTTPURLResponse {
            if response.isForMainFrame && http.statusCode >= 400 {
                retryURL = http.url
                showFailure("服务暂时不可用（HTTP \(http.statusCode)）。")
                decisionHandler(.cancel)
                return
            }
            let disposition = http.value(forHTTPHeaderField: "Content-Disposition")?.lowercased()
            if disposition?.hasPrefix("attachment") == true || !response.canShowMIMEType {
                decisionHandler(.download)
                return
            }
        }
        decisionHandler(.allow)
    }

    func webView(_ webView: WKWebView, navigationAction: WKNavigationAction, didBecome download: WKDownload) {
        download.delegate = downloads
    }

    func webView(_ webView: WKWebView, navigationResponse: WKNavigationResponse, didBecome download: WKDownload) {
        download.delegate = downloads
    }

    func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
        failure.isHidden = true
        web.isHidden = false
        spinner.startAnimating()
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        spinner.stopAnimating()
        if site.contains(web.url) { retryURL = web.url }
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        navigationFailed(error)
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        navigationFailed(error)
    }

    private func navigationFailed(_ error: Error) {
        spinner.stopAnimating()
        let nsError = error as NSError
        // Intentional navigation cancellation (redirects, downloads) does not lose the current page.
        if (nsError.domain == NSURLErrorDomain && nsError.code == NSURLErrorCancelled) ||
            (nsError.domain == "WebKitErrorDomain" && nsError.code == 102) { return }
        if let url = nsError.userInfo[NSURLErrorFailingURLErrorKey] as? URL, site.contains(url) { retryURL = url }
        showFailure("无法载入工作空间。请检查网络后重新连接。")
    }

    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        if site.contains(webView.url) { retryURL = webView.url }
        showFailure("页面进程已结束。重新连接后可查看服务端保存的任务。")
    }

    func webView(_ webView: WKWebView, runJavaScriptConfirmPanelWithMessage message: String,
                 initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping (Bool) -> Void) {
        guard site.contains(frame.request.url), frame.isMainFrame,
              presentedViewController == nil else { completionHandler(false); return }
        let prompt = UIAlertController(title: "yangbin AI", message: message, preferredStyle: .alert)
        prompt.addAction(UIAlertAction(title: "取消", style: .cancel) { _ in completionHandler(false) })
        prompt.addAction(UIAlertAction(title: "确定", style: .default) { _ in completionHandler(true) })
        present(prompt, animated: true)
    }
}
