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
 * Copyright © 2019–2026 Konrad Kollnig
 */

package net.kollnig.missioncontrol

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.kollnig.missioncontrol.data.InsightsData
import net.kollnig.missioncontrol.data.InsightsDataProvider
import net.kollnig.missioncontrol.ui.compose.InsightsScreen
import net.kollnig.missioncontrol.ui.compose.InsightsScreenCallbacks
import net.kollnig.missioncontrol.ui.compose.InsightsScreenController
import net.kollnig.missioncontrol.ui.compose.InsightsScreenModel
import java.io.File
import java.io.FileOutputStream
import java.text.NumberFormat
import java.util.Locale

/**
 * Activity displaying privacy insights and tracking statistics for the past 7 days.
 * Shows impactful visualizations of how widespread tracking is and how TrackerControl protects.
 */
class InsightsActivity : AppCompatActivity() {

    private lateinit var dataProvider: InsightsDataProvider
    private lateinit var screenController: InsightsScreenController
    private var currentData: InsightsData? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val composeView = ComposeView(this)
        // A stable ID gives Compose's saveable state registry a key to persist
        // under, so the LazyColumn scroll position survives recreation.
        composeView.id = R.id.compose_insights
        setContentView(composeView)

        // Set up action bar
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setTitle(R.string.insights_title)
        }

        screenController = InsightsScreen.install(
            composeView = composeView,
            initialModel = InsightsScreenModel.loading(),
            callbacks = object : InsightsScreenCallbacks {
                override fun onShare() {
                    if (!isDestroyed && !isFinishing) {
                        shareInsights()
                    }
                }
            }
        )

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
        screenController.update(InsightsScreenModel.loading())

        lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) {
                dataProvider.computeInsights()
            }
            if (isDestroyed) return@launch
            displayData(data)
        }
    }

    private fun displayData(data: InsightsData) {
        if (!data.hasData()) {
            screenController.update(InsightsScreenModel.from(data))
            return
        }

        currentData = data
        screenController.update(InsightsScreenModel.from(data))
    }

    /**
     * Share privacy insights as an image with text.
     */
    private fun shareInsights() {
        val data = currentData ?: return

        lifecycleScope.launch {
            try {
                val imageFile = withContext(Dispatchers.IO) {
                    generateShareImage(data)
                }

                if (imageFile != null) {
                    val uri = FileProvider.getUriForFile(
                        this@InsightsActivity,
                        "${packageName}.provider",
                        imageFile
                    )

                    val shareText = getString(
                        R.string.insights_share_message,
                        data.blockedTrackingAttempts,
                        data.uniqueTrackerCompanies
                    )

                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_TEXT, shareText)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }

                    startActivity(Intent.createChooser(intent, getString(R.string.insights_share)))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to share insights", e)
                Toast.makeText(this@InsightsActivity, R.string.export_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun generateShareImage(data: InsightsData): File? {
        return try {
            // Inflate and populate view
            val inflater = LayoutInflater.from(this)
            val shareView = inflater.inflate(R.layout.layout_insights_share, null)

            val tvTotalBlocked = shareView.findViewById<TextView>(R.id.tvShareTotalBlocked)
            val llBlockedStat = shareView.findViewById<LinearLayout>(R.id.llShareBlockedStat)
            val tvBlockedCount = shareView.findViewById<TextView>(R.id.tvShareBlockedCount)
            val tvCompanies = shareView.findViewById<TextView>(R.id.tvShareCompanies)
            val llTopCompanies = shareView.findViewById<LinearLayout>(R.id.llShareTopCompanies)

            val nf = NumberFormat.getNumberInstance(Locale.getDefault())
            
            // Hero stat: Total Hosts
            tvTotalBlocked.text = nf.format(data.totalTrackingAttempts)

            llBlockedStat.visibility = View.VISIBLE
            tvBlockedCount.text = nf.format(data.blockedTrackingAttempts)

            // Companies count
            tvCompanies.text = data.uniqueTrackerCompanies.toString()
            
            // Top 3 Companies (dynamically added) - use pervasiveTrackers for correct app counts
            val top3 = data.pervasiveTrackers.take(3)
            val density = resources.displayMetrics.density
            
            for (company in top3) {
                val row = LinearLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = (4 * density).toInt()
                    }
                    orientation = LinearLayout.HORIZONTAL
                }
                
                val nameView = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    text = company.first
                    setTextColor(Color.WHITE)
                    textSize = 12f
                }
                
                val countView = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    text = getString(R.string.insights_in_apps, company.second)
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    setTypeface(null, Typeface.BOLD)
                }
                
                row.addView(nameView)
                row.addView(countView)
                llTopCompanies.addView(row)
            }

            // Measure and layout
            val width = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 400f, resources.displayMetrics).toInt()
            val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
            val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            
            shareView.measure(widthSpec, heightSpec)
            shareView.layout(0, 0, shareView.measuredWidth, shareView.measuredHeight)

            // Draw to bitmap
            val bitmap = Bitmap.createBitmap(shareView.measuredWidth, shareView.measuredHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            shareView.draw(canvas)

            // Save
            val shareDir = File(cacheDir, "share")
            if (!shareDir.exists()) shareDir.mkdirs()
            
            val imageFile = File(shareDir, "trackercontrol_insights.png")
            FileOutputStream(imageFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            bitmap.recycle()
            
            imageFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate share image", e)
            null
        }
    }

    companion object {
        private const val TAG = "InsightsActivity"
    }
}
