package knf.kuma.emision

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.viewpager.widget.ViewPager
import com.google.android.material.tabs.TabLayout
import knf.kuma.BottomFragment
import knf.kuma.R
import knf.kuma.commons.EAHelper
import knf.kuma.commons.PrefsUtil
import knf.kuma.widgets.emision.WEmisionProvider
import java.util.Calendar

/** Emisión como pestaña de la barra inferior (mismo contenido que EmissionActivityMaterial). */
class EmissionBottomFragment : BottomFragment() {
    private var pager: ViewPager? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_emission_bottom, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        setHasOptionsMenu(true)
        val pager = view.findViewById<ViewPager>(R.id.pager).also { this.pager = it }
        pager.offscreenPageLimit = 7
        pager.adapter = EmissionPagerAdapter(childFragmentManager)
        view.findViewById<TabLayout>(R.id.tabs).setupWithViewPager(pager)
        val day = Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1
        pager.setCurrentItem((if (day == 0) 7 else day) - 1, false)
        EAHelper.clear2()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_emision, menu)
        if (PrefsUtil.emissionShowHidden)
            menu.findItem(R.id.action_hideshow)?.setIcon(R.drawable.ic_hide_pref)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_hideshow) {
            PrefsUtil.emissionShowHidden = !PrefsUtil.emissionShowHidden
            requireActivity().invalidateOptionsMenu()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onPause() {
        super.onPause()
        WEmisionProvider.update(requireContext().applicationContext)
    }

    override fun onReselect() {}
}
