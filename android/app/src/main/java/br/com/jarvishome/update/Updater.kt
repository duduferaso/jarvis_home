package br.com.jarvishome.update

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Atualização pelo GitHub: a branch "atualizacoes" do repositório guarda só a última versão
 * (versao.json + JarvisHome-<versão>-<código>.apk). Se ela não existir, tenta o GitHub Releases.
 * Repositório público dispensa chave; privado usa uma chave só de leitura. O APK baixado é
 * conferido pelo SHA-256 do versao.json.
 */
object Updater {

    class Release(val versionCode: Int, val versionName: String, val notes: String,
                  val assetApiUrl: String, val downloadUrl: String, val size: Long, val sha256: String = "")

    const val BRANCH = "atualizacoes"

    /** Lê o versao.json da branch de atualizações. */
    fun parseManifest(m: JSONObject, rawBase: String): Release? {
        val code = m.optInt("versionCode", 0)
        val apk = m.optString("apk")
        if (code <= 0 || !ASSET.matches(apk)) return null
        val url = "$rawBase/$apk"
        return Release(code, m.optString("versionName"), m.optString("notes").trim(), url, url,
            m.optLong("size", 0L), m.optString("sha256").lowercase())
    }

    fun rawBase(repo: String) = "https://raw.githubusercontent.com/${repo.trim().trim('/')}/$BRANCH"

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
        val base = rawBase(repo)
        // 1º: API do GitHub (sem o cache de até 5 min dos servidores de arquivos)
        try {
            val a = open("https://api.github.com/repos/${repo.trim().trim('/')}/contents/versao.json?ref=$BRANCH",
                token, "application/vnd.github.raw+json")
            try {
                if (a.responseCode == 200) return parseManifest(JSONObject(a.inputStream.bufferedReader().use { it.readText() }), base)
            } finally {
                a.disconnect()
            }
        } catch (_: Exception) {
            // limite da API ou sem rede para ela: usa o arquivo direto
        }
        val m = open("$base/versao.json?t=" + System.currentTimeMillis() / 60000, token, "application/json")
        try {
            val code = m.responseCode
            if (code == 200) return parseManifest(JSONObject(m.inputStream.bufferedReader().use { it.readText() }), base)
            if (code != 404) throw HttpError(code, describe(code, token))
        } finally {
            m.disconnect()
        }
        return latestRelease(repo, token)
    }

    private fun latestRelease(repo: String, token: String): Release? {
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
                if (r.sha256.isNotEmpty() && sha256(tmp) != r.sha256) {
                    tmp.delete()
                    throw IOException("O arquivo baixado não confere (SHA-256 diferente)")
                }
                dest.delete()
                if (!tmp.renameTo(dest)) throw IOException("Não consegui salvar o arquivo")
                return
            } finally {
                c.disconnect()
            }
        }
    }

    fun sha256(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
