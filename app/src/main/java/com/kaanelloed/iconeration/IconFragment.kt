package com.kaanelloed.iconeration

import android.content.SharedPreferences
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.drawable.toBitmap
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kaanelloed.iconeration.databinding.FragmentIconBinding

class IconFragment : Fragment() {

    private var _binding: FragmentIconBinding? = null
    private var apps: Array<PackageInfoStruct>? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = FragmentIconBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val am = ApplicationManager()
        apps = activity?.packageManager?.let { it1 -> am.getInstalledApps(it1, false) }
        view.findViewById<RecyclerView>(R.id.appView).apply {
            layoutManager = LinearLayoutManager(view.context)
            adapter = apps?.let { AppListAdapter(it, PreferenceManager.getDefaultSharedPreferences(this.context)) }
        }

        binding.buttonSecond.setOnClickListener {
            //findNavController().navigate(R.id.action_SecondFragment_to_FirstFragment)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

class AppListAdapter(private val dataSet: Array<PackageInfoStruct>, private val prefs: SharedPreferences): RecyclerView.Adapter<AppListAdapter.ViewHolder>() {
    var edgeDetector = CannyEdgeDetector()

    class ViewHolder(view: View): RecyclerView.ViewHolder(view) {
        val appIcon: ImageView
        val genIcon: ImageView
        val appName: TextView

        init {
            // Define click listener for the ViewHolder's View
            appIcon = view.findViewById(R.id.appIcon)
            genIcon = view.findViewById(R.id.genIcon)
            appName = view.findViewById(R.id.appName)
        }
    }

    override fun onCreateViewHolder(viewGroup: ViewGroup, viewType: Int): ViewHolder {
        // Create a new view, which defines the UI of the list item
        val view = LayoutInflater.from(viewGroup.context)
            .inflate(R.layout.app_info_item, viewGroup, false)

        return ViewHolder(view)
    }

    // Replace the contents of a view (invoked by the layout manager)
    override fun onBindViewHolder(viewHolder: ViewHolder, position: Int) {

        // Get element from your dataset at this position and replace the
        // contents of the view with that element
        val app = dataSet[position]
        if (app.icon.minimumHeight == 0) return

        edgeDetector = CannyEdgeDetector()
        edgeDetector.process(app.icon.toBitmap(), prefs.getString("edgeColor", "-1")!!.toInt())

        viewHolder.appIcon.setImageDrawable(app.icon)
        viewHolder.genIcon.setImageBitmap(edgeDetector.edgesImage)
        viewHolder.appName.text = app.appName
    }

    // Return the size of your dataset (invoked by the layout manager)
    override fun getItemCount() = dataSet.size
}