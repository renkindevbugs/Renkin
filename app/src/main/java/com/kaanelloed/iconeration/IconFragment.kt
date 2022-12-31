package com.kaanelloed.iconeration

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.graphics.drawable.toBitmap
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.kaanelloed.iconeration.databinding.FragmentIconBinding

class IconFragment : Fragment() {

    private var loaded = false
    private var _binding: FragmentIconBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requireActivity().onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (loaded)
                    findNavController().popBackStack()
                else
                    Snackbar.make(
                        binding.root,
                        "Please wait until the process is complete",
                        Snackbar.LENGTH_LONG
                    )
                        .setAnchorView(R.id.nav_host_fragment_content_main)
                        .setAction("Wait", null).show()
            }
        })
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = FragmentIconBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnCreatePack.setOnClickListener {
            findNavController().navigate(R.id.action_IconFragment_to_iconPackFragment)
        }

        Thread {
            val progBar = activity?.findViewById<ProgressBar>(R.id.progress_loader)!!

            view.post {
                requireActivity().window.setFlags(
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
                progBar.visibility = View.VISIBLE
            }

            val act = requireActivity() as MainActivity

            val includeAvailable = PreferencesHelper(view.context).getIncludeAvailableIcon()

            if (act.apps == null || act.currentPack != act.lastPack) {
                val am = ApplicationManager(activity?.packageManager!!)

                if (act.currentPack == null) {
                    act.apps = am.getInstalledApps()
                } else {
                    act.apps = am.getMissingPackageApps(act.currentPack!!, includeAvailable)
                }

                act.apps!!.sort()

                val color = PreferencesHelper(view.context).getIconColor()
                val genType = PreferencesHelper(view.context).getGenType()
                IconGenerator(view.context, act.apps!!, color).generateIcons(genType)

                act.lastPack = act.currentPack
            }

            view.post {
                view.findViewById<RecyclerView>(R.id.appView).apply {
                    layoutManager = LinearLayoutManager(view.context)
                    adapter = AppListAdapter(act.apps!!)
                }

                progBar.visibility = View.INVISIBLE
                binding.btnCreatePack.isEnabled = true
                requireActivity().window.clearFlags(
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            }

            loaded = true

        }.start()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    class AppListAdapter(private val dataSet: Array<PackageInfoStruct>): RecyclerView.Adapter<AppListAdapter.ViewHolder>() {
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

            viewHolder.appIcon.setImageDrawable(app.icon)
            viewHolder.genIcon.setImageBitmap(app.genIcon)
            viewHolder.appName.text = app.appName
        }

        // Return the size of your dataset (invoked by the layout manager)
        override fun getItemCount() = dataSet.size
    }
}