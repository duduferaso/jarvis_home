package br.com.jarvishome.update

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Atualização pelo GitHub Releases: lê a última versão publicada em github.com/<repo>/releases e
 * baixa o APK. Repositório público dispensa chave; privado usa uma chave só de leitura.
 * O APK publicado é chamado JarvisHome-<versão>-<código>.apk (ex.: JarvisHome-1.7.0-12.apk).
 */
object Updater {

    class Release(val versionCode: Int, val versionName: String, val notes: String,
                  val assetApiUrl: String, val downloadUrl: String, val size: Long)

    class HttpError(val code: Int, message: String) : IOException(message)

    private val ASSET = Regex("""^JarvisHome-([0-9][0-9A-Za-z.\-]*)-(\d+)\.apk$""")

    /** Lê a resposta de /releases/latest. Null se não houver APK com o nome esperado. */
    fun parse(release: JSONObject): Release? {
        val assets = release.optJSONArray("assets") ?: return null
        var best: Release? = null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val m = ASSET.find(a.optString("name")) ?: continue
            val code = m.groupValues[2].toIntOrNull() ?: continue
            if (best == null || code > best.versionCode) {
                val notes = if (release.has("body") && !release.isNull("body")) release.optString("body") else ""
                best = Release(code, m.groupValues[1], notes.trim(), a.optString("url"),
                    a.optString("browser_download_url"), a.optLong("size", 0L))
            }
        }
        return best
    }

    private fun open(url: String, token: String, accept: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 20000
        c.instanceFollowRedirects = false
        c.setRequestProperty("Accept", accept)
        c.setRequestProperty("User-Agent", "JarvisHome-Updater")
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        if (token.isNotBlank()) c.setRequestProperty("Authorization", "Bearer " + token.trim())
        return c
    }

    private fun describe(code: Int, token: String) = when (code) {
        401 -> "chave do GitHub recusada (HTTP 401)"
        403 -> "o GitHub recusou ou limitou o acesso (HTTP 403)"
        404 -> if (token.isBlank()) "nenhuma versão publicada, ou o repositório é privado e falta a chave (HTTP 404)"
               else "nenhuma versão publicada ou a chave não vê este repositório (HTTP 404)"
        else -> "HTTP $code"
    }

    fun latest(repo: String, token: String): Release? {
        val c = open("https://api.github.com/repos/${repo.trim().trim('/')}/releases/latest", token, "application/vnd.github+json")
        try {
            val code = c.responseCode
            if (code != 200) throw HttpError(code, describe(code, token))
            return parse(JSONObject(c.inputStream.bufferedReader().use { it.readText() }))
        } finally {
            c.disconnect()
        }
    }

    /** Baixa o APK para [dest]. A chave só vai ao GitHub, nunca ao servidor de arquivos do redirecionamento. */
    fun download(r: Release, token: String, dest: File, progress: (Int) -> Unit) {
        var url = if (token.isBlank()) r.downloadUrl else r.assetApiUrl
        var auth = token
        var hops = 0
        while (true) {
            val c = open(url, auth, "application/octet-stream")
            try {
                val code = c.responseCode
                if (code in 300..399) {
                    url = c.getHeaderField("Location") ?: throw IOException("Redirecionamento sem destino")
                    if (!URL(url).host.endsWith("api.github.com")) auth = ""
                    if (++hops > 5) throw IOException("Redirecionamentos demais")
                    continue
                }
                if (code != 200) throw HttpError(code, describe(code, token))
                val total = if (c.contentLengthLong > 0) c.contentLengthLong else r.size
                val tmp = File(dest.path + ".part")
                c.inputStream.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var last = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val pct = if (total > 0) (done * 100 / total).toInt() else 0
                            if (pct != last) { last = pct; progress(pct) }
                        }
                    }
                }
                if (r.size > 0 && tmp.length() != r.size) throw IOException("Download incompleto (${tmp.length()} de ${r.size} bytes)")
                dest.delete()
                if (!tmp.renameTo(dest)) throw IOException("Não consegui salvar o arquivo")
                return
            } finally {
                c.disconnect()
            }
        }
    }
}
