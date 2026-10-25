package app.mytv.player

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var loginPanel: View
    private lateinit var contentPanel: View
    private lateinit var loadingPanel: View
    private lateinit var tvLoading: TextView
    private lateinit var tvLoginError: TextView
    private lateinit var tvAccount: TextView
    private lateinit var etServer: EditText
    private lateinit var etUser: EditText
    private lateinit var etPass: EditText
    private lateinit var etSearch: EditText
    private lateinit var rvChannels: RecyclerView

    private val catAdapter = RowAdapter<Category>({ it.name }) { _, pos -> selectCategory(pos) }
    private val chAdapter = RowAdapter<Channel>({ it.name }) { _, pos -> openChannel(pos) }

    private var currentCat: Category = ALL
    private var visible: List<Channel> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        loginPanel = findViewById(R.id.loginPanel)
        contentPanel = findViewById(R.id.contentPanel)
        loadingPanel = findViewById(R.id.loadingPanel)
        tvLoading = findViewById(R.id.tvLoading)
        tvLoginError = findViewById(R.id.tvLoginError)
        tvAccount = findViewById(R.id.tvAccount)
        etServer = findViewById(R.id.etServer)
        etUser = findViewById(R.id.etUser)
        etPass = findViewById(R.id.etPass)
        etSearch = findViewById(R.id.etSearch)
        rvChannels = findViewById(R.id.rvChannels)

        val rvCategories = findViewById<RecyclerView>(R.id.rvCategories)
        rvCategories.layoutManager = LinearLayoutManager(this)
        rvCategories.adapter = catAdapter
        rvChannels.layoutManager = LinearLayoutManager(this)
        rvChannels.adapter = chAdapter

        findViewById<Button>(R.id.btnLogin).setOnClickListener { doLogin() }
        findViewById<Button>(R.id.btnLogout).setOnClickListener { logout() }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = applyFilter()
        })

        etServer.setText(prefs.server.ifBlank { "http://liontv.es:8080" })
        etUser.setText(prefs.user)
        etPass.setText(prefs.pass)

        if (prefs.hasAccount) loadData() else show(loginPanel)
    }

    private fun show(panel: View) {
        loginPanel.visibility = if (panel === loginPanel) View.VISIBLE else View.GONE
        contentPanel.visibility = if (panel === contentPanel) View.VISIBLE else View.GONE
        loadingPanel.visibility = if (panel === loadingPanel) View.VISIBLE else View.GONE
    }

    private fun doLogin() {
        val server = etServer.text.toString().trim()
        val user = etUser.text.toString().trim()
        val pass = etPass.text.toString().trim()
        if (server.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            tvLoginError.text = "Completa los tres campos"
            return
        }
        prefs.server = XtreamApi.normalize(server)
        prefs.user = user
        prefs.pass = pass
        loadData()
    }

    private fun logout() {
        prefs.clearAccount()
        Repo.clear()
        etUser.setText("")
        etPass.setText("")
        tvLoginError.text = ""
        show(loginPanel)
    }

    private fun loadData() {
        tvLoading.text = "Cargando canales…"
        show(loadingPanel)
        lifecycleScope.launch {
            try {
                val api = XtreamApi(prefs.server, prefs.user, prefs.pass)
                val (info, cats, chans) = withContext(Dispatchers.IO) {
                    Triple(api.login(), api.categories(), api.channels())
                }
                Repo.categories = listOf(ALL) + cats
                Repo.channels = chans

                val parts = mutableListOf<String>()
                parts += if (info.status.isNotBlank()) info.status else "Cuenta"
                if (info.maxConnections.isNotBlank() && info.maxConnections != "null") {
                    parts += "conexiones ${info.activeConnections}/${info.maxConnections}"
                }
                info.expiry?.let { parts += "vence $it" }
                parts += "${chans.size} canales"
                tvAccount.text = parts.joinToString(" · ")

                catAdapter.submit(Repo.categories)
                currentCat = ALL
                catAdapter.select(0)
                applyFilter()
                show(contentPanel)
                rvChannels.post { rvChannels.requestFocus() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                tvLoginError.text = friendly(e)
                show(loginPanel)
            }
        }
    }

    private fun friendly(e: Exception): String = when (e) {
        is IOException -> "No se pudo conectar al servidor. Revisa la URL y tu conexión."
        is JSONException -> "El servidor devolvió una respuesta que no se pudo leer."
        else -> e.message ?: "Error desconocido"
    }

    private fun selectCategory(pos: Int) {
        currentCat = Repo.categories.getOrNull(pos) ?: ALL
        catAdapter.select(pos)
        applyFilter()
    }

    private fun applyFilter() {
        val q = etSearch.text.toString().trim()
        val base = if (currentCat.id == ALL.id) Repo.channels
        else Repo.channels.filter { it.categoryId == currentCat.id }
        visible = if (q.isEmpty()) base else base.filter { it.name.contains(q, ignoreCase = true) }
        chAdapter.submit(visible)
    }

    private fun openChannel(pos: Int) {
        Repo.playlist = visible
        startActivity(Intent(this, PlayerActivity::class.java).putExtra("index", pos))
    }

    companion object {
        private val ALL = Category("*", "Todos")
    }
}
