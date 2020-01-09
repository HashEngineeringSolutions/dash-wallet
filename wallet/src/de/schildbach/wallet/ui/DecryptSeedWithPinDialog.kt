package de.schildbach.wallet.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.ui.preference.PinRetryController
import org.bitcoinj.wallet.DeterministicSeed

/**
 * @author:  Eric Britten
 *
 * DecryptSeedWithPinDialog uses DecryptSeedSharedModel which is derived
 * from CheckPinShared model but does not call the onCorrectPinCallback
 * event
 */

class DecryptSeedWithPinDialog : CheckPinDialog() {

    companion object {

        private val FRAGMENT_TAG = DecryptSeedWithPinDialog::class.java.simpleName

        private const val ARG_REQUEST_CODE = "arg_request_code"
        private const val ARG_PIN_ONLY = "arg_pin_only"

        @JvmStatic
        fun show(activity: AppCompatActivity, requestCode: Int = 0, pinOnly: Boolean = false) {
            val checkPinDialog = DecryptSeedWithPinDialog()
            if (PinRetryController.getInstance().isLocked) {
                checkPinDialog.showLockedAlert(activity)
            } else {
                val args = Bundle()
                args.putInt(ARG_REQUEST_CODE, requestCode)
                args.putBoolean(ARG_PIN_ONLY, pinOnly)
                checkPinDialog.arguments = args
                checkPinDialog.show(activity.supportFragmentManager, FRAGMENT_TAG)
            }
        }

        @JvmStatic
        fun show(activity: AppCompatActivity, requestCode: Int = 0) {
            show(activity, requestCode, false)
        }

    }

    override fun initViewModel() {
        viewModel = ViewModelProviders.of(this).get(DecryptSeedViewModel::class.java)
        (viewModel as DecryptSeedViewModel).decryptSeedLiveData.observe(viewLifecycleOwner, Observer {
            when (it.status) {
                Status.ERROR -> {
                    pinRetryController.failedAttempt(it.data!!.second!!)
                    if (pinRetryController.isLocked) {
                        showLockedAlert(context!!)
                        dismiss()
                        return@Observer
                    }
                    setState(State.INVALID_PIN)
                }
                Status.LOADING -> {
                    setState(State.DECRYPTING)
                }
                Status.SUCCESS -> {
                    dismiss(it.data!!.first!!, it.data!!.second!!)
                }
            }
        })
    }

    private fun dismiss(seed : DeterministicSeed, pin: String) {
        if (pinRetryController.isLocked) {
            return
        }
        val requestCode = arguments!!.getInt(ARG_REQUEST_CODE)
        (sharedModel as DecryptSeedSharedModel).onDecryptSeedCallback.value = Pair(requestCode, seed)
        pinRetryController.clearPinFailPrefs()
        dismiss()
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        activity?.run {
            sharedModel = ViewModelProviders.of(this)[DecryptSeedSharedModel::class.java]
        } ?: throw IllegalStateException("Invalid Activity")
    }
}
