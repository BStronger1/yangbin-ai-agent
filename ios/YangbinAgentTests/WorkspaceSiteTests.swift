import XCTest
@testable import YangbinAgent

final class WorkspaceSiteTests: XCTestCase {
    let site = WorkspaceSite.production

    func testWorkspaceRoutes() {
        XCTAssertEqual(site.url(for: .coding).path, "/coding")
        XCTAssertEqual(site.url(for: .chat).absoluteString, "https://www.yangbinai.homes/assistant?mode=chat")
        XCTAssertEqual(site.url(for: .work).query, "mode=work")
        XCTAssertEqual(site.url(for: .blog).path, "/blog")
        XCTAssertEqual(site.url(for: .knowledge).path, "/knowledge")
    }

    func testOriginsAndDownloads() {
        for url in ["https://www.yangbinai.homes/coding", "https://WWW.YANGBINAI.HOMES:443/login"] {
            XCTAssertTrue(site.contains(URL(string: url)))
        }
        for url in ["http://www.yangbinai.homes", "https://www.yangbinai.homes:444",
                    "https://www.yangbinai.homes.attacker.test", "https://x@www.yangbinai.homes/",
                    "javascript:alert(1)", "file:///private/file"] {
            XCTAssertFalse(site.contains(URL(string: url)))
        }
        XCTAssertTrue(site.allowsDownload(URL(string: "blob:https://www.yangbinai.homes/1234")))
        XCTAssertFalse(site.allowsDownload(URL(string: "blob:https://other.test/1234")))
        XCTAssertFalse(site.contains(URL(string: "blob:https://www.yangbinai.homes/1234")))
    }

    func testDevelopmentUsesWholeHttpsOrigin() {
        XCTAssertNil(WorkspaceSite(URL(string: "http://localhost:8080")!))
        XCTAssertNil(WorkspaceSite(URL(string: "https://host.test/path")!))
        XCTAssertNil(WorkspaceSite(URL(string: "https://host.test?secret=q")!))
        let dev = WorkspaceSite(URL(string: "https://staging.test:8443")!)
        XCTAssertEqual(dev?.url(for: .coding).absoluteString, "https://staging.test:8443/coding")
        XCTAssertEqual(dev?.url(for: .blog).absoluteString, "https://staging.test:8443/blog")
        XCTAssertEqual(dev?.contains(URL(string: "https://staging.test")), false)
    }

    func testOnlyPublicArticlePathsCanBeShared() {
        let id = "87c47d69-73da-4b9a-8cfd-88d4f142359f"
        let publicURL = site.origin.appendingPathComponent("p").appendingPathComponent(id)
        XCTAssertEqual(site.publicArticleURL(publicURL), publicURL)
        XCTAssertEqual(site.publicArticleURL(URL(string: publicURL.absoluteString + "?from=reader#comments")), publicURL)
        for path in ["/blog/" + id, "/blog/" + id + "/edit", "/p/not-an-id",
                     "/p/" + id + "/edit", "/p/" + id.uppercased()] {
            XCTAssertNil(site.publicArticleURL(URL(string: path, relativeTo: site.origin)?.absoluteURL))
        }
        XCTAssertNil(site.publicArticleURL(URL(string: "https://other.example/p/" + id)))
    }
}
