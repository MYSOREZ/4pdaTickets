package ru.fourpda.tickets

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Восстановленная реализация экрана "Мои тикеты".
 * Берёт сохранённые тикеты из SharedPreferences (временное решение)
 * и отображает их через TicketAdapter. При отсутствии данных показывает сообщение.
 */
class TicketListActivity : AppCompatActivity() {
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: TicketAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ticket_list)

        // Кнопка "назад"
        findViewById<View>(R.id.btnBack)?.setOnClickListener { finish() }
        // Кнопка "обновить"
        findViewById<View>(R.id.btnRefreshTickets)?.setOnClickListener {
            loadTicketsAndRender()
        }

        recycler = findViewById(R.id.recyclerTickets)
        recycler.layoutManager = LinearLayoutManager(this)
        adapter = TicketAdapter { /* onTicketClick: открытие уже реализовано внутри адаптера */ }
        recycler.adapter = adapter

        loadTicketsAndRender()
    }

    private fun loadTicketsAndRender() {
        // Временное восстановление: читаем список из SharedPreferences,
        // куда QuickCheckWorker/TicketMonitor положили последний найденный список в JSON-формате
        // Ключи и формат берём совместимые с проектом: "last_ticket_json" для одного тикета
        val prefs = getSharedPreferences("ticket_stats", MODE_PRIVATE)
        val raw = prefs.getString("last_ticket_json", "") ?: ""

        val textMessage = findViewById<android.widget.TextView>(R.id.textMessage)
        val statsContainer = findViewById<View>(R.id.statsContainer)
        val progress = findViewById<View>(R.id.progressBar)

        progress.visibility = View.GONE
        statsContainer.visibility = View.GONE

        if (raw.isBlank()) {
            textMessage.visibility = View.VISIBLE
            textMessage.text = "Нет сохранённых тикетов для показа"
            adapter.setTickets(emptyList())
            return
        }

        try {
            val items = mutableListOf<TicketData>()
            val arr = org.json.JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                items.add(
                    TicketData(
                        id = o.optString("id"),
                        title = o.optString("title"),
                        section = o.optString("section"),
                        date = o.optString("date"),
                        topic = o.optString("topic"),
                        description = o.optString("description"),
                        sender = o.optString("sender"),
                        status = 0, // показываем только новые (status-0) — совместимо с текущей логикой
                        moderator = "",
                        postAuthor = ""
                    )
                )
            }

            textMessage.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            if (items.isEmpty()) {
                textMessage.text = "Список пуст"
            }
            adapter.setTickets(items)
        } catch (e: Exception) {
            textMessage.visibility = View.VISIBLE
            textMessage.text = "Ошибка загрузки тикетов: ${e.message}"
            adapter.setTickets(emptyList())
        }
    }
}
