package com.example.grama_yatri

import android.app.AlertDialog
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class BusAdapter(
    initialStops: List<BusStop>,
    private var status: BusStatus,
    private val onPingClick: (Int, String) -> Unit,
    private val onReportClick: (Int, String) -> Unit,
    private val onItemClick: (Int) -> Unit
) : RecyclerView.Adapter<BusAdapter.ViewHolder>() {

    private var stops: MutableList<BusStop> = initialStops.toMutableList()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val stopName: TextView = view.findViewById(R.id.stopName)
        val etaValue: TextView = view.findViewById(R.id.etaValue)
        val reportedByText: TextView = view.findViewById(R.id.reportedByText)
        val pingButton: Button = view.findViewById(R.id.pingButton)
        val reportButton: Button = view.findViewById(R.id.reportButton)
        val lineTop: View = view.findViewById(R.id.lineTop)
        val lineBottom: View = view.findViewById(R.id.lineBottom)
        val dot: ImageView = view.findViewById(R.id.dot)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_stop, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val stop = stops[position]
        holder.stopName.text = stop.name
        
        holder.lineTop.visibility = if (position == 0) View.INVISIBLE else View.VISIBLE
        holder.lineBottom.visibility = if (position == itemCount - 1) View.INVISIBLE else View.VISIBLE
        
        if (position == status.lastStopIndex) {
            holder.dot.setColorFilter(Color.BLUE)
            holder.reportedByText.visibility = View.VISIBLE
            holder.reportedByText.text = "Reported by ${status.reportedBy}"
        } else if (position < status.lastStopIndex) {
            holder.dot.setColorFilter(Color.GRAY)
            holder.reportedByText.visibility = View.GONE
        } else {
            holder.dot.setColorFilter(Color.LTGRAY)
            holder.reportedByText.visibility = View.GONE
        }

        when {
            status.isCanceled() -> {
                holder.etaValue.text = "Canceled"
                holder.etaValue.setTextColor(Color.RED)
            }
            status.isBreakdown() -> {
                holder.etaValue.text = "Breakdown Reported"
                holder.etaValue.setTextColor(Color.parseColor("#FF8F00"))
            }
            position == status.lastStopIndex -> {
                holder.etaValue.text = "Bus is here"
                holder.etaValue.setTextColor(Color.BLUE)
            }
            position < status.lastStopIndex -> {
                holder.etaValue.text = "Passed"
                holder.etaValue.setTextColor(Color.GRAY)
            }
            else -> {
                val minutes = calculateMinutes(position)
                holder.etaValue.text = if (status.lastStopIndex == -1) "Waiting for ping..." else "Exp. Time: ~$minutes mins"
                holder.etaValue.setTextColor(Color.parseColor("#2E7D32"))
            }
        }

        holder.pingButton.setOnClickListener {
            val options = arrayOf("I am on the bus", "The bus just passed me")
            AlertDialog.Builder(holder.itemView.context)
                .setTitle("Update Bus Status")
                .setItems(options) { _, which ->
                    onPingClick(position, options[which])
                }
                .show()
        }

        holder.reportButton.setOnClickListener {
            val issues = arrayOf("Canceled", "Breakdown")
            AlertDialog.Builder(holder.itemView.context)
                .setTitle("Report Issue")
                .setItems(issues) { _, which ->
                    onReportClick(position, issues[which].uppercase())
                }
                .show()
        }
        
        holder.itemView.setOnClickListener { onItemClick(position) }
    }

    private fun calculateMinutes(targetIndex: Int): Int {
        if (status.lastStopIndex == -1) return 0
        var total = 0
        for (i in status.lastStopIndex until targetIndex) {
            total += stops[i].avgTimeToNext
        }
        val elapsed = ((System.currentTimeMillis() - status.timestamp) / 60000).toInt()
        return (total - elapsed).coerceAtLeast(1)
    }

    fun updateData(newStops: List<BusStop>, newStatus: BusStatus) {
        this.stops = newStops.toMutableList()
        this.status = newStatus
        notifyDataSetChanged()
    }

    override fun getItemCount() = stops.size
}