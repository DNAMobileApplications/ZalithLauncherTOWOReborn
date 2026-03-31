package com.movtery.zalithlauncher.ui.subassembly.customprofilepath

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.RadioButton
import androidx.recyclerview.widget.RecyclerView
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.databinding.ItemProfilePathBinding
import com.movtery.zalithlauncher.databinding.ViewPathManagerBinding
import com.movtery.zalithlauncher.feature.customprofilepath.ProfilePathManager
import com.movtery.zalithlauncher.feature.customprofilepath.ScopedVersionsManager
import com.movtery.zalithlauncher.feature.version.VersionsManager
import com.movtery.zalithlauncher.setting.AllSettings.Companion.launcherProfile
import com.movtery.zalithlauncher.ui.dialog.EditTextDialog
import com.movtery.zalithlauncher.ui.dialog.TipDialog
import com.movtery.zalithlauncher.ui.fragment.FilesFragment
import com.movtery.zalithlauncher.ui.fragment.FragmentWithAnim
import com.movtery.zalithlauncher.utils.StoragePermissionsUtils
import com.movtery.zalithlauncher.utils.ZHTools

class ProfilePathAdapter(
    private val fragment: FragmentWithAnim,
    private val view: RecyclerView
) : RecyclerView.Adapter<ProfilePathAdapter.ViewHolder>() {

    private val mData: MutableList<ProfileItem> = ArrayList()
    private val radioButtonList: MutableList<RadioButton> = mutableListOf()

    // If storage permission is unavailable, force the selection back to the default path.
    private var currentId: String? =
        if (StoragePermissionsUtils.checkPermissions()) launcherProfile.getValue() else "default"

    private val managerPopupWindow: PopupWindow = PopupWindow().apply {
        isFocusable = true
        isOutsideTouchable = true
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(
            ItemProfilePathBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        )
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.setView(mData[position], position)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        radioButtonList.remove(holder.binding.radioButton)
    }

    override fun getItemCount(): Int = mData.size

    @SuppressLint("NotifyDataSetChanged")
    fun updateData(data: MutableList<ProfileItem>) {
        currentId = if (StoragePermissionsUtils.checkPermissions()) {
            launcherProfile.getValue()
        } else {
            "default"
        }

        mData.clear()
        mData.addAll(data)

        radioButtonList.apply {
            forEach { it.isChecked = false }
            clear()
        }

        notifyDataSetChanged()
        view.scheduleLayoutAnimation()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun refresh() {
        ProfilePathManager.save(mData)
        ProfilePathManager.refreshPath()
        currentId = if (StoragePermissionsUtils.checkPermissions()) {
            launcherProfile.getValue()
        } else {
            "default"
        }
        notifyDataSetChanged()
        view.scheduleLayoutAnimation()
    }

    fun closePopupWindow() {
        managerPopupWindow.dismiss()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun setSelectedProfile(profileItem: ProfileItem) {
        val context = fragment.requireContext().applicationContext

        currentId = profileItem.id

        ProfilePathManager.init(context)
        if (profileItem.path.startsWith("content://")) {
            ScopedVersionsManager.init(context)
        }

        ProfilePathManager.setCurrentPathId(profileItem.id)

        radioButtonList.forEach { radioButton ->
            radioButton.isChecked = radioButton.tag.toString() == profileItem.id
        }

        notifyDataSetChanged()
        view.scheduleLayoutAnimation()
    }

    inner class ViewHolder(val binding: ItemProfilePathBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun setView(profileItem: ProfileItem, position: Int) {
            binding.apply {
                radioButtonList.add(
                    radioButton.apply {
                        tag = profileItem.id
                        isChecked = currentId == profileItem.id
                    }
                )

                title.text = profileItem.title
                path.text = profileItem.path
                path.isSelected = true

                val onClickListener = View.OnClickListener {
                    if (VersionsManager.canRefresh() && currentId != profileItem.id) {
                        StoragePermissionsUtils.checkPermissions(
                            activity = fragment.requireActivity(),
                            title = R.string.profiles_path_title,
                            permissionGranted = object : StoragePermissionsUtils.PermissionGranted {
                                override fun granted() {
                                    setSelectedProfile(profileItem)
                                }

                                override fun cancelled() {}
                            }
                        )
                    }
                }

                root.setOnClickListener(onClickListener)
                radioButton.setOnClickListener(onClickListener)

                operate.setOnClickListener {
                    showPopupWindow(root, profileItem.id == "default", profileItem, position)
                }
            }
        }

        private fun showPopupWindow(
            anchorView: View,
            isDefault: Boolean,
            profileItem: ProfileItem,
            itemIndex: Int
        ) {
            val context = anchorView.context

            val viewBinding = ViewPathManagerBinding.inflate(LayoutInflater.from(context)).apply {
                val onClickListener = View.OnClickListener { v ->
                    when (v) {
                        gotoView -> {
                            val bundle = Bundle()
                            bundle.putString(
                                FilesFragment.BUNDLE_LOCK_PATH,
                                Environment.getExternalStorageDirectory().absolutePath
                            )
                            bundle.putString(FilesFragment.BUNDLE_LIST_PATH, profileItem.path)
                            ZHTools.swapFragmentWithAnim(
                                fragment,
                                FilesFragment::class.java,
                                FilesFragment.TAG,
                                bundle
                            )
                        }

                        rename -> {
                            EditTextDialog.Builder(context)
                                .setTitle(R.string.generic_rename)
                                .setEditText(profileItem.title)
                                .setAsRequired()
                                .setConfirmListener { editBox, _ ->
                                    val newTitle = editBox.text.toString()

                                    if (profileItem.path.startsWith("content://")) {
                                        ProfilePathManager.renameScopedProfile(
                                            context,
                                            profileItem.id,
                                            newTitle
                                        )
                                        mData[itemIndex].title = newTitle
                                        notifyItemChanged(itemIndex)
                                        view.scheduleLayoutAnimation()
                                    } else {
                                        mData[itemIndex].title = newTitle
                                        refresh()
                                    }
                                    true
                                }
                                .showDialog()
                        }

                        delete -> {
                            TipDialog.Builder(context)
                                .setTitle(context.getString(R.string.profiles_path_delete_title))
                                .setMessage(R.string.profiles_path_delete_message)
                                .setCancelable(false)
                                .setConfirmClickListener {
                                    val wasCurrent = currentId == profileItem.id

                                    if (profileItem.path.startsWith("content://")) {
                                        ProfilePathManager.removeScopedProfileById(
                                            context,
                                            profileItem.id
                                        )
                                    } else {
                                        ProfilePathManager.removePathById(profileItem.id)
                                    }

                                    mData.removeAt(itemIndex)

                                    if (wasCurrent) {
                                        val defaultItem = mData.firstOrNull { it.id == "default" }
                                        if (defaultItem != null) {
                                            setSelectedProfile(defaultItem)
                                        } else {
                                            currentId = "default"
                                            notifyDataSetChanged()
                                            view.scheduleLayoutAnimation()
                                        }
                                    } else {
                                        notifyItemRemoved(itemIndex)
                                        notifyItemRangeChanged(itemIndex, mData.size - itemIndex)
                                        view.scheduleLayoutAnimation()
                                    }
                                }
                                .showDialog()
                        }

                        else -> {}
                    }
                    managerPopupWindow.dismiss()
                }

                gotoView.setOnClickListener(onClickListener)
                rename.setOnClickListener(onClickListener)
                delete.setOnClickListener(onClickListener)

                if (isDefault) {
                    renameLayout.visibility = View.GONE
                    deleteLayout.visibility = View.GONE
                }
            }

            managerPopupWindow.apply {
                viewBinding.root.measure(0, 0)
                contentView = viewBinding.root
                width = viewBinding.root.measuredWidth
                height = viewBinding.root.measuredHeight
                showAsDropDown(anchorView, anchorView.measuredWidth, 0)
            }
        }
    }
}
