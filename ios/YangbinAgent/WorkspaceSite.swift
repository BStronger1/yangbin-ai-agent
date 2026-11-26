import Foundation

struct WorkspaceSite {
    static let production = WorkspaceSite(URL(string: "https://www.yangbinai.homes")!)!

    let origin: URL

    init?(_ url: URL) {
        guard Self.isHTTPS(url), ["", "/"].contains(url.path),
              url.query == nil, url.fragment == nil else { return nil }
        origin = url
    }

    static var configured: WorkspaceSite {
        #if DEBUG
        if let value = ProcessInfo.processInfo.environment["YANGBIN_DEV_ORIGIN"],
           let url = URL(string: value), let site = WorkspaceSite(url) {
            return site
        }
        #endif
        return production
    }

    static func isHTTPS(_ url: URL) -> Bool {
        url.scheme?.lowercased() == "https" && url.host?.isEmpty == false &&
            url.user == nil && url.password == nil
    }

    func contains(_ url: URL?) -> Bool {
        guard let url, Self.isHTTPS(url) else { return false }
        return url.host?.lowercased() == origin.host?.lowercased() &&
            (url.port ?? 443) == (origin.port ?? 443)
    }

    func allowsDownload(_ url: URL?) -> Bool {
        guard let url else { return false }
        if url.scheme == "blob" {
            return contains(URL(string: String(url.absoluteString.dropFirst(5))))
        }
        return contains(url)
    }

    enum Workspace: String, CaseIterable {
        case coding = "Coding"
        case chat = "Chat"
        case work = "Work"
        case blog = "我的博客"
        case knowledge = "我的知识库"
    }

    func url(for workspace: Workspace) -> URL {
        switch workspace {
        case .coding: return origin.appendingPathComponent("coding")
        case .blog: return origin.appendingPathComponent("blog")
        case .knowledge: return origin.appendingPathComponent("knowledge")
        case .chat, .work:
            var parts = URLComponents(url: origin.appendingPathComponent("assistant"), resolvingAgainstBaseURL: false)!
            parts.queryItems = [URLQueryItem(name: "mode", value: workspace == .chat ? "chat" : "work")]
            return parts.url!
        }
    }

    func publicArticleURL(_ url: URL?) -> URL? {
        guard let url, contains(url),
              let parts = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return nil }
        let path = parts.percentEncodedPath
        guard path.hasPrefix("/p/") else { return nil }
        let id = String(path.dropFirst(3))
        guard let uuid = UUID(uuidString: id), id == uuid.uuidString.lowercased() else { return nil }
        return origin.appendingPathComponent("p").appendingPathComponent(id)
    }
}
