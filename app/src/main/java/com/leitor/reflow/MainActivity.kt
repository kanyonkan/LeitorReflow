package com.leitor.reflow

import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

class MainActivity : AppCompatActivity() {

    private val main = Handler(Looper.getMainLooper())
    private val pages = ArrayList<String>()
    private lateinit var adapter: PageAdapter
    private lateinit var root: LinearLayout
    private lateinit var bar: LinearLayout
    private lateinit var status: TextView
    private lateinit var list: RecyclerView
    private val buttons = ArrayList<Button>()
    private lateinit var themeBtn: Button

    private var night = false
    private var textSp = 18f
    private var loadToken = 0

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) load(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)

        val prefs = getSharedPreferences("leitor", MODE_PRIVATE)
        night = prefs.getBoolean("night", false)
        textSp = prefs.getFloat("textSp", 18f)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
        }

        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        bar.addView(makeBtn("Abrir") { picker.launch(arrayOf("application/pdf")) })
        bar.addView(makeBtn("A−") { changeSize(-1f) })
        bar.addView(makeBtn("A+") { changeSize(+1f) })
        themeBtn = makeBtn("") { toggleTheme() }
        bar.addView(themeBtn)
        root.addView(bar, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        status = TextView(this).apply {
            text = "Toque em Abrir e escolha um PDF."
            textSize = 14f
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        root.addView(status, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        adapter = PageAdapter()
        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
            setItemViewCacheSize(4)
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        applyTheme()

        // PDF aberto por outro app ("Abrir com...")
        intent?.data?.let { load(it) }
    }

    private fun makeBtn(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            setOnClickListener { onClick() }
            buttons.add(this)
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(dp(3), 0, dp(3), 0) }
        }

    private fun changeSize(delta: Float) {
        textSp = (textSp + delta).coerceIn(12f, 34f)
        getSharedPreferences("leitor", MODE_PRIVATE).edit().putFloat("textSp", textSp).apply()
        adapter.notifyDataSetChanged()
    }

    private fun toggleTheme() {
        night = !night
        getSharedPreferences("leitor", MODE_PRIVATE).edit().putBoolean("night", night).apply()
        applyTheme()
    }

    private fun applyTheme() {
        val bg = if (night) Color.parseColor("#121212") else Color.parseColor("#FAF8F3")
        val fg = if (night) Color.parseColor("#D6D3CB") else Color.parseColor("#1B1B1B")
        val barBg = if (night) Color.parseColor("#1E1E1E") else Color.parseColor("#E9E5DA")
        root.setBackgroundColor(bg)
        bar.setBackgroundColor(barBg)
        status.setTextColor(fg)
        buttons.forEach {
            it.setTextColor(fg)
            it.setBackgroundColor(if (night) Color.parseColor("#2C2C2C") else Color.parseColor("#D8D3C4"))
        }
        themeBtn.text = if (night) "☀ Dia" else "☾ Noite"
        adapter.notifyDataSetChanged()
    }

    private fun load(uri: Uri) {
        val token = ++loadToken
        pages.clear()
        adapter.notifyDataSetChanged()
        status.text = "Abrindo…"

        Thread {
            try {
                contentResolver.openInputStream(uri)!!.use { input ->
                    PDDocument.load(input).use { doc ->
                        val total = doc.numberOfPages
                        val stripper = PDFTextStripper().apply { sortByPosition = true }
                        var withText = 0
                        for (i in 1..total) {
                            if (token != loadToken) return@Thread // outro arquivo foi aberto
                            stripper.startPage = i
                            stripper.endPage = i
                            val text = Reflow.reflow(stripper.getText(doc))
                            if (text.isNotBlank()) withText++
                            main.post {
                                if (token != loadToken) return@post
                                pages.add(text)
                                adapter.notifyItemInserted(pages.size - 1)
                                status.text = if (i < total) "Carregando página $i de $total…"
                                else "$total páginas"
                            }
                        }
                        if (withText == 0) {
                            main.post {
                                if (token != loadToken) return@post
                                status.text = "Este PDF não tem texto (é só imagem). Faça o OCR antes de abrir."
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                main.post {
                    status.text = "Não foi possível abrir o PDF."
                    Toast.makeText(this, e.message ?: "Erro", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private inner class PageAdapter : RecyclerView.Adapter<PageAdapter.VH>() {

        inner class VH(val box: LinearLayout, val label: TextView, val body: TextView) :
            RecyclerView.ViewHolder(box)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val box = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setPadding(dp(20), dp(14), dp(20), dp(18))
            }
            val label = TextView(parent.context).apply {
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                alpha = 0.55f
            }
            val body = TextView(parent.context).apply {
                typeface = Typeface.SERIF
                setLineSpacing(0f, 1.4f)
            }
            box.addView(label)
            box.addView(body)
            return VH(box, label, body)
        }

        override fun getItemCount() = pages.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val fg = if (night) Color.parseColor("#D6D3CB") else Color.parseColor("#1B1B1B")
            h.label.setTextColor(fg)
            h.label.text = "— ${position + 1} —"
            h.body.setTextColor(fg)
            h.body.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
            h.body.text = pages[position].ifBlank { "(página sem texto)" }
        }
    }
}
