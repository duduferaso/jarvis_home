import br.com.jarvishome.update.Updater
import org.json.JSONObject
import java.io.File

var fl = 0
fun ok(c: Boolean, m: String) { if (!c) fl++; println((if (c) "PASSOU " else "FALHOU ") + m) }

fun main(args: Array<String>) {
    val base = args[0]
    val rel = JSONObject("""{"tag_name":"v1.7.0","body":"Novidades\n- botão de atualização","assets":[
        {"name":"notas.txt","url":"x","browser_download_url":"y","size":3},
        {"name":"JarvisHome-1.7.0-12.apk","url":"$base/api/asset/1","browser_download_url":"$base/download/JarvisHome-1.7.0-12.apk","size":200000},
        {"name":"JarvisHome-1.6.9-11.apk","url":"$base/api/asset/0","browser_download_url":"$base/old.apk","size":10}]}""")
    val r = Updater.parse(rel)
    ok(r != null && r.versionCode == 12 && r.versionName == "1.7.0", "acha o APK mais novo pelo nome: ${r?.versionName} (${r?.versionCode})")
    ok(r?.notes?.startsWith("Novidades") == true, "lê as notas da versão")
    ok(Updater.parse(JSONObject("""{"assets":[{"name":"app.apk"}]}""")) == null, "ignora arquivos com outro nome")

    val f = File.createTempFile("upd", ".apk"); f.delete()
    var last = -1
    Updater.download(r!!, "", f) { last = it }
    ok(f.length() == 200000L && last == 100, "repositório público: baixa pelo link direto (${f.length()} bytes)")
    f.delete()
    Updater.download(r, "CHAVE_TESTE", f) { }
    ok(f.length() == 200000L, "repositório privado: baixa pela API com a chave")
    val log = java.net.URL("$base/log").readText()
    println("   servidor viu: $log")
    ok(log.contains("asset:auth=yes") && log.contains("file:auth=no"), "a chave vai para a API e NÃO segue para o servidor de arquivos")
    val bad = Updater.Release(13, "x", "", "$base/api/asset/1", "$base/download/x", 999999)
    try { Updater.download(bad, "", f) { }; ok(false, "download incompleto devia falhar") }
    catch (e: Exception) { ok(e.message?.contains("incompleto") == true, "detecta download incompleto: ${e.message}") }
    val data = File.createTempFile("ref", ".bin"); data.writeBytes(ByteArray(200000) { (it % 256).toByte() }.also { for (i in 199936 until 200000) it[i] = 0 })
    val sha = Updater.sha256(data)
    val m = Updater.parseManifest(JSONObject("""{"versionCode":13,"versionName":"1.7.1","notes":"teste","apk":"JarvisHome-1.7.1-13.apk","size":200000,"sha256":"$sha"}"""), "$base/download")
    ok(m != null && m.versionCode == 13 && m.downloadUrl == "$base/download/JarvisHome-1.7.1-13.apk", "lê o versao.json da branch")
    ok(Updater.parseManifest(JSONObject("""{"versionCode":13,"apk":"outro.apk"}"""), base) == null, "recusa versao.json com nome de APK estranho")
    f.delete(); Updater.download(m!!, "", f) { }
    ok(f.length() == 200000L, "baixa e confere o SHA-256")
    val wrong = Updater.Release(13, "1.7.1", "", m.assetApiUrl, m.downloadUrl, 200000, "00" + sha.drop(2))
    f.delete()
    try { Updater.download(wrong, "", f) { }; ok(false, "SHA errado devia falhar") }
    catch (e: Exception) { ok(e.message?.contains("SHA-256") == true && !f.exists(), "recusa arquivo adulterado: ${e.message}") }
    println(if (fl == 0) "TODOS PASSARAM" else "$fl FALHARAM"); if (fl > 0) System.exit(1)
}
