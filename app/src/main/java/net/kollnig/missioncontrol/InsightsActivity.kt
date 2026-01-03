/*
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * TrackerControl is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with TrackerControl. If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright © 2019–2020 Konrad Kollnig (University of Oxford)
 */

package net.kollnig.missioncontrol

import android.animation.ValueAnimator
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Pair
import android.util.TypedValue
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.kollnig.missioncontrol.data.InsightsData
import net.kollnig.missioncontrol.data.InsightsDataProvider
import java.text.NumberFormat
import java.util.Locale

/**
 * Activity displaying privacy insights and tracking statistics for the past 7 days.
 * Shows impactful visualizations of how widespread tracking is and how TrackerControl protects.
 */
class InsightsActivity : AppCompatActivity() {

    private lateinit var tvTotalAttempts: TextView
    private lateinit var tvShockingFact: TextView
    private lateinit var tvBlockedCount: TextView
    private lateinit var tvBlockedPercent: TextView
    private lateinit var tvAllowedCount: TextView
    private lateinit var tvAllowedPercent: TextView
    private lateinit var tvCompaniesCount: TextView
    private lateinit var tvAppsCount: TextView
    private lateinit var llTopApps: LinearLayout
    private lateinit var llTopCompanies: LinearLayout
    private lateinit var llDailyChart: LinearLayout
    private lateinit var llNoData: LinearLayout
    private lateinit var progressBar: ProgressBar

    private lateinit var dataProvider: InsightsDataProvider

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_insights)

        // Set up action bar
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setTitle(R.string.insights_title)
        }

        // Initialize views
        tvTotalAttempts = findViewById(R.id.tvTotalAttempts)
        tvShockingFact = findViewById(R.id.tvShockingFact)
        tvBlockedCount = findViewById(R.id.tvBlockedCount)
        tvBlockedPercent = findViewById(R.id.tvBlockedPercent)
        tvAllowedCount = findViewById(R.id.tvAllowedCount)
        tvAllowedPercent = findViewById(R.id.tvAllowedPercent)
        tvCompaniesCount = findViewById(R.id.tvCompaniesCount)
        tvAppsCount = findViewById(R.id.tvAppsCount)
        llTopApps = findViewById(R.id.llTopApps)
        llTopCompanies = findViewById(R.id.llTopCompanies)
        llDailyChart = findViewById(R.id.llDailyChart)
        llNoData = findViewById(R.id.llNoData)
        progressBar = findViewById(R.id.progressBar)

        dataProvider = InsightsDataProvider(this)

        loadData()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun loadData() {
        progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) {
                dataProvider.computeInsights()
            }
            progressBar.visibility = View.GONE
            displayData(data)
        }
    }

    private fun displayData(data: InsightsData) {
        if (!data.hasData()) {
            llNoData.visibility = View.VISIBLE
            return
        }

        llNoData.visibility = View.GONE

        // Animate main number
        animateNumber(tvTotalAttempts, 0, data.totalTrackingAttempts)

        // Set shocking fact
        tvShockingFact.text = getString(
            R.string.insights_shocking_fact,
            data.uniqueTrackerCompanies,
            data.totalTrackingAttempts
        )

        // Blocked/Allowed stats
        animateNumber(tvBlockedCount, 0, data.blockedTrackingAttempts)
        val blockedPercent = data.getBlockedPercentage()
        tvBlockedPercent.text = String.format(Locale.getDefault(), "(%d%%)", blockedPercent)

        animateNumber(tvAllowedCount, 0, data.allowedTrackingAttempts)
        val allowedPercent = 100 - blockedPercent
        tvAllowedPercent.text = String.format(Locale.getDefault(), "(%d%%)", allowedPercent)

        // Summary stats
        animateNumber(tvCompaniesCount, 0, data.uniqueTrackerCompanies)
        animateNumber(tvAppsCount, 0, data.appsWithTrackers)

        // Populate top apps
        populateTopList(llTopApps, data.topTrackingApps)

        // Populate top companies
        populateTopList(llTopCompanies, data.topTrackerCompanies)

        // Draw daily chart
        drawDailyChart(data.trackingByDay, data.blockedByDay)
    }

    private fun animateNumber(textView: TextView, start: Int, end: Int) {
        ValueAnimator.ofInt(start, end).apply {
            duration = 1000
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                val value = animation.animatedValue as Int
                textView.text = NumberFormat.getNumberInstance(Locale.getDefault()).format(value)
            }
            start()
        }
    }

    private fun populateTopList(container: LinearLayout, items: List<Pair<String, Int>>) {
        container.removeAllViews()

        if (items.isEmpty()) {
            val emptyText = TextView(this).apply {
                setText(R.string.none)
                alpha = 0.5f
            }
            container.addView(emptyText)
            return
        }

        val maxCount = items.firstOrNull()?.second ?: 1

        for (item in items) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 8)
                gravity = Gravity.CENTER_VERTICAL
            }

            // Name
            val nameView = TextView(this).apply {
                text = item.first
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                maxLines = 1
            }
            row.addView(nameView)

            // Bar + Count wrapper
            val barWrapper = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            // Progress bar visualization
            var barWidth = (150 * (item.second.toFloat() / maxCount)).toInt()
            barWidth = barWidth.coerceAtLeast(8)

            val bar = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, barWidth.toFloat(), resources.displayMetrics).toInt(),
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f, resources.displayMetrics).toInt()
                ).apply {
                    marginEnd = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics).toInt()
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4f, resources.displayMetrics)
                    setColor(ContextCompat.getColor(this@InsightsActivity, R.color.colorPrimary))
                }
            }
            barWrapper.addView(bar)

            // Count
            val countView = TextView(this).apply {
                text = NumberFormat.getNumberInstance(Locale.getDefault()).format(item.second)
                textSize = 12f
            }
            barWrapper.addView(countView)

            row.addView(barWrapper)
            container.addView(row)
        }
    }

    private fun drawDailyChart(trackingByDay: Map<String, Int>, blockedByDay: Map<String, Int>) {
        llDailyChart.removeAllViews()

        if (trackingByDay.isEmpty()) return

        // Find max for scaling
        val maxValue = trackingByDay.values.maxOrNull() ?: 1

        val colorPrimary = ContextCompat.getColor(this, R.color.colorPrimary)
        val colorAllowed = ContextCompat.getColor(this, R.color.colorGrayed)

        for ((dayLabel, total) in trackingByDay) {
            val dayColumn = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    setMargins(4, 0, 4, 0)
                }
            }

            val blocked = blockedByDay[dayLabel] ?: 0
            val allowed = total - blocked

            // Calculate bar heights
            val maxBarHeight = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 60f, resources.displayMetrics).toInt()
            val blockedHeight = if (maxValue > 0) (maxBarHeight * (blocked.toFloat() / maxValue)).toInt() else 0
            val allowedHeight = if (maxValue > 0) (maxBarHeight * (allowed.toFloat() / maxValue)).toInt() else 0

            // Stacked bar (allowed on top, blocked on bottom)
            val stackedBar = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.BOTTOM
            }

            // Allowed part (on top, lighter color)
            if (allowedHeight > 0) {
                val allowedBar = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, allowedHeight)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadii = floatArrayOf(8f, 8f, 8f, 8f, 0f, 0f, 0f, 0f)
                        setColor(colorAllowed)
                    }
                }
                stackedBar.addView(allowedBar)
            }

            // Blocked part (on bottom, primary color)
            if (blockedHeight > 0) {
                val blockedBar = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, blockedHeight)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadii = if (allowedHeight == 0) {
                            floatArrayOf(8f, 8f, 8f, 8f, 8f, 8f, 8f, 8f)
                        } else {
                            floatArrayOf(0f, 0f, 0f, 0f, 8f, 8f, 8f, 8f)
                        }
                        setColor(colorPrimary)
                    }
                }
                stackedBar.addView(blockedBar)
            }

            dayColumn.addView(stackedBar)

            // Day label
            val labelView = TextView(this).apply {
                text = dayLabel
                textSize = 10f
                gravity = Gravity.CENTER
            }
            dayColumn.addView(labelView)

            llDailyChart.addView(dayColumn)
        }
    }
}
