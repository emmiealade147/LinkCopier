package com.linkcopier.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    companion object {
        // Google's own infrastructure/ad/tracking domains — never real search results.
        private val GOOGLE_INFRA_HOST_REGEX = Regex(
            "(^|\\.)google\\.[a-z.]+$|(^|\\.)gstatic\\.com$|(^|\\.)googleusercontent\\.com$|" +
                "(^|\\.)googleadservices\\.com$|(^|\\.)googlesyndication\\.com$|" +
                "(^|\\.)doubleclick\\.net$|(^|\\.)ggpht\\.com$|(^|\\.)googleapis\\.com$",
            RegexOption.IGNORE_CASE
        )
    }

    private lateinit var webView: WebView
    private lateinit var urlInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        urlInput = findViewById(R.id.urlInput)
        val goButton = findViewById<Button>(R.id.goButton)
        val copyButton = findViewById<Button>(R.id.copyButton)

        webView.settings.javaScriptEnabled = true
        webView.webViewClient = WebViewClient()

        goButton.setOnClickListener { loadTypedUrl() }
        urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                loadTypedUrl()
                true
            } else {
                false
            }
        }

        copyButton.setOnClickListener { copyLinksFromWebView() }

        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    /** Handles being opened via Chrome's Share menu, or via "Open with". */
    private fun handleIncomingIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                val url = extractUrl(sharedText)
                if (url != null) {
                    urlInput.setText(url)
                    fetchAndCopyLinks(url)
                } else {
                    Toast.makeText(this, "No link found in what was shared", Toast.LENGTH_SHORT).show()
                }
            }
            Intent.ACTION_VIEW -> {
                intent.dataString?.let {
                    urlInput.setText(it)
                    webView.loadUrl(it)
                }
            }
        }
    }

    /**
     * Google (and similar search-result pages) wrap real destinations in a
     * redirect like "https://www.google.com/url?q=<real link>&sa=...".
     * This unwraps that so we get the actual website, not Google's wrapper.
     */
    private fun unwrapRedirect(rawLink: String): String {
        return try {
            val uri = URI(rawLink)
            val host = uri.host?.lowercase() ?: return rawLink
            if (!GOOGLE_INFRA_HOST_REGEX.containsMatchIn(host)) return rawLink
            if (uri.path != "/url" || uri.query == null) return rawLink

            val params = uri.query.split("&")
            val qParam = params.firstOrNull { it.startsWith("q=") }
                ?: params.firstOrNull { it.startsWith("url=") }
            val encoded = qParam?.substringAfter("=") ?: return rawLink
            URLDecoder.decode(encoded, "UTF-8")
        } catch (e: Exception) {
            rawLink
        }
    }

    /** True only for well-formed, absolute http/https links pointing at a
     * real external site — filters out javascript:, mailto:, tel:, bare
     * "#" anchors, malformed URLs, and Google's own nav/tracking domains. */
    private fun isValidLink(link: String): Boolean {
        if (link.isBlank()) return false
        return try {
            val uri = URI(link)
            val scheme = uri.scheme?.lowercase()
            val host = uri.host
            if (!(scheme == "http" || scheme == "https") || host.isNullOrBlank()) return false
            !GOOGLE_INFRA_HOST_REGEX.containsMatchIn(host.lowercase())
        } catch (e: Exception) {
            false
        }
    }

    private fun extractUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val regex = Regex("(https?://\\S+)")
        return regex.find(text)?.value
    }

    private fun loadTypedUrl() {
        var url = urlInput.text.toString().trim()
        if (url.isEmpty()) return
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }
        urlInput.setText(url)
        webView.loadUrl(url)
    }

    /**
     * Copies links from the page currently loaded in the in-app WebView.
     * This reads the *live, rendered* DOM, so it also works on pages that
     * build their links with JavaScript.
     */
    private fun copyLinksFromWebView() {
        val js = """
            (function() {
                var links = Array.from(document.querySelectorAll('a[href]'))
                    .map(function(a) { return a.href; })
                    .filter(function(href) { return /^https?:\/\//i.test(href); });
                return JSON.stringify(links);
            })();
        """.trimIndent()

        webView.evaluateJavascript(js) { result ->
            try {
                val cleaned = result.trim('"').replace("\\\"", "\"")
                val jsonArray = JSONArray(cleaned)
                val links = (0 until jsonArray.length())
                    .map { jsonArray.getString(it) }
                    .map { unwrapRedirect(it) }
                    .distinct()
                    .filter { isValidLink(it) }
                copyToClipboard(links)
            } catch (e: Exception) {
                Toast.makeText(this, "Couldn't read links from this page", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Fetches a URL's HTML server-side and extracts links — used for the
     * Share-menu flow, so it works without leaving your regular browser.
     * Note: this won't see links that a page only builds with JavaScript;
     * for those, open the page in this app's own browser above instead.
     */
    private fun fetchAndCopyLinks(url: String) {
        Toast.makeText(this, "Fetching links…", Toast.LENGTH_SHORT).show()
        thread {
            try {
                val doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Android) LinkCopier")
                    .timeout(15000)
                    .get()
                val links = doc.select("a[href]")
                    .map { it.absUrl("href") }
                    .map { unwrapRedirect(it) }
                    .distinct()
                    .filter { isValidLink(it) }
                runOnUiThread { copyToClipboard(links) }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Couldn't fetch that page: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun copyToClipboard(links: List<String>) {
        if (links.isEmpty()) {
            Toast.makeText(this, "No valid links found on this page", Toast.LENGTH_SHORT).show()
            return
        }

        // Valid web pages and valid file links (pdf, docx, zip, images, etc.)
        // go together in one plain list, one per line.
        val text = links.joinToString("\n")

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Links", text))
        Toast.makeText(this, "Copied ${links.size} link(s) to clipboard", Toast.LENGTH_LONG).show()
    }
}
