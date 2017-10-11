/**
 * Copyright (c) 2017  Brendan Molloy
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package so.brendan.smsmailer

import android.Manifest
import android.accounts.AccountManager
import android.app.Activity
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Bundle
import android.support.v7.app.AlertDialog
import android.support.v7.app.AppCompatActivity
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.util.ExponentialBackOff
import com.google.api.services.gmail.GmailScopes
import pub.devrel.easypermissions.AfterPermissionGranted
import pub.devrel.easypermissions.EasyPermissions
import java.util.*
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

const val REQUEST_ACCOUNT_PICKER = 1000
const val REQUEST_AUTHORIZATION = 1001
const val REQUEST_GOOGLE_PLAY_SERVICES = 1002
const val REQUEST_PERMISSION_GET_ACCOUNTS = 1003
const val REQUEST_RESEND_TEST = 1004
const val PREF_ACCOUNT_NAME = "accountName"
const val PREF_ID = "prefs"

val SCOPES = listOf(GmailScopes.GMAIL_INSERT)

class MainActivity : AppCompatActivity(), EasyPermissions.PermissionCallbacks {
    lateinit private var credential: GoogleAccountCredential
    private var isLoading = false
    private var progress: ProgressDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initCredential()
        attemptUpdateCredential()
        setContentView(R.layout.activity_main)
        initButtons()
        refreshState()
    }

    private fun initCredential() {
        credential = GoogleAccountCredential
                .usingOAuth2(applicationContext, SCOPES)
                .setBackOff(ExponentialBackOff())
    }

    private fun initButtons() {
        val btnEnableAccount = findViewById(R.id.btn_enable_account) as Button
        val btnSendTestEmail = findViewById(R.id.btn_send_test_email) as Button
        val btnDisableAccount = findViewById(R.id.btn_disable_account) as Button

        btnEnableAccount.setOnClickListener {
            onClickChooseAccount()
        }

        btnDisableAccount.setOnClickListener {
            onClickDisableForwarding()
        }

        btnSendTestEmail.setOnClickListener {
            onClickSendTestEmail()
        }
    }

    private fun refreshState() {
        val btnEnableAccount = findViewById(R.id.btn_enable_account) as Button
        val btnSendTestEmail = findViewById(R.id.btn_send_test_email) as Button
        val btnDisableAccount = findViewById(R.id.btn_disable_account) as Button
        val txtStatus = findViewById(R.id.txt_status) as TextView
        val contentView = findViewById(R.id.content_view)

        if (isLoading) {
            contentView.visibility = View.GONE
            return
        }

        contentView.visibility = View.VISIBLE

        if (credential.selectedAccountName == null) {
            txtStatus.text = String.format(getString(R.string.disabled_info), getString(R.string.enable_account))
            btnEnableAccount.visibility = View.VISIBLE
            btnDisableAccount.visibility = View.GONE
            btnSendTestEmail.visibility = View.GONE
        } else {
            txtStatus.text = String.format(getString(R.string.enabled_info), credential.selectedAccountName)
            btnEnableAccount.visibility = View.GONE
            btnDisableAccount.visibility = View.VISIBLE
            btnSendTestEmail.visibility = View.VISIBLE
        }
    }

    private fun ensureAccountEnabled(): Boolean {
        return if (!isGooglePlayServicesAvailable) {
            acquireGooglePlayServices()
            false
        } else if (credential.selectedAccountName == null) {
            chooseAccount()
            false
        } else {
            true
        }
    }

    private fun onClickChooseAccount() {
        if (ensureAccountEnabled()) {
            refreshState()
        }
    }

    private fun onClickDisableForwarding() {
        val settings = getSharedPreferences(PREF_ID, Context.MODE_PRIVATE)
        val editor = settings.edit()
        editor.remove(PREF_ACCOUNT_NAME)
        editor.apply()
        initCredential()
        refreshState()
    }

    private fun saveCurrentCredentials() {
        val settings = getSharedPreferences(PREF_ID, Context.MODE_PRIVATE)
        val editor = settings.edit()
        editor.putString(PREF_ACCOUNT_NAME, credential.selectedAccountName)
        editor.apply()
    }

    private val savedAccountName get(): String? =
        getSharedPreferences(PREF_ID, Context.MODE_PRIVATE)
                .getString(PREF_ACCOUNT_NAME, null)

    private fun sendTestEmail(callback: () -> Unit = {}) {
        var msg = MimeMessage(Session.getDefaultInstance(Properties()))
        val emailTo = credential.selectedAccountName
        val from = getString(R.string.test_email)

        msg.setFrom(InternetAddress(emailTo.emailWithPlus(from), getString(R.string.app_name)))
        msg.addRecipient(javax.mail.Message.RecipientType.TO, InternetAddress(emailTo))
        msg.subject = getString(R.string.sms_mailer_enabled)
        msg.setText(getString(R.string.test_email_body))

        GmailSender(credential, getString(R.string.app_name)).insertEmail(msg, { res ->
            runOnUiThread {
                when (res) {
                    is EmailError -> startActivityForResult(res.e.intent, REQUEST_RESEND_TEST)
                    is EmailMessage -> {
                        Toast.makeText(
                                this, R.string.test_email_sent, Toast.LENGTH_SHORT).show()
                        saveCurrentCredentials()
                        isLoading = false
                        progress?.dismiss()
                    }
                }

                refreshState()
                callback()
            }
        })
    }

    private fun onClickSendTestEmail() {
        if (!isDeviceOnline) {
            Toast.makeText(
                    this, R.string.not_online, Toast.LENGTH_SHORT).show()
            return
        }

        sendTestEmail()
    }

    private val isDeviceOnline: Boolean
        get() {
            val connMgr = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val networkInfo = connMgr.activeNetworkInfo
            return networkInfo != null && networkInfo.isConnected
        }

    private val isGooglePlayServicesAvailable: Boolean
        get() {
            val apiAvailability = GoogleApiAvailability.getInstance()
            val connectionStatusCode = apiAvailability.isGooglePlayServicesAvailable(this)
            return connectionStatusCode == ConnectionResult.SUCCESS
        }

    @AfterPermissionGranted(REQUEST_PERMISSION_GET_ACCOUNTS)
    private fun attemptUpdateCredential() {
        if (EasyPermissions.hasPermissions(this, Manifest.permission.GET_ACCOUNTS)) {
            val accountName = savedAccountName

            if (accountName != null) {
                credential.selectedAccountName = accountName
            }
        }
    }

    private fun chooseAccount() {
        attemptUpdateCredential()

        if (credential.selectedAccountName == null) {
            isLoading = true
            startActivityForResult(
                    credential.newChooseAccountIntent(),
                    REQUEST_ACCOUNT_PICKER)
        } else {
           requestPermissions()
        }
    }

    private fun requestPermissions() {
        EasyPermissions.requestPermissions(
                this,
                getString(R.string.permissions_desc),
                REQUEST_PERMISSION_GET_ACCOUNTS,
                Manifest.permission.GET_ACCOUNTS,
                Manifest.permission.RECEIVE_SMS)
    }

    private fun startLoading() {
        progress = ProgressDialog(this)
        progress?.isIndeterminate = true
        progress?.setTitle(R.string.enabling_sms_mailer)
        progress?.show()
    }

    override fun onActivityResult(
            requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQUEST_GOOGLE_PLAY_SERVICES -> if (resultCode != Activity.RESULT_OK) {
                AlertDialog.Builder(this)
                        .setTitle(R.string.gplay_title)
                        .setMessage(R.string.gplay_msg)
                        .show()
            } else {
                attemptUpdateCredential()
            }
            REQUEST_ACCOUNT_PICKER -> if (resultCode == Activity.RESULT_OK && data != null &&
                    data.extras != null) {
                val accountName = data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
                if (accountName != null) {
                    credential.selectedAccountName = accountName
                    startLoading()
                    sendTestEmail()
                }
            }
            REQUEST_AUTHORIZATION -> if (resultCode == Activity.RESULT_OK) {
                attemptUpdateCredential()
            }
            REQUEST_RESEND_TEST -> {
                isLoading = false

                if (resultCode == Activity.RESULT_OK) {
                    sendTestEmail()
                } else {
                    initCredential()
                    refreshState()
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int,
                                            permissions: Array<String>,
                                            grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        EasyPermissions.onRequestPermissionsResult(
                requestCode, permissions, grantResults, this)
    }

    override fun onPermissionsGranted(requestCode: Int, list: List<String>) {
        // Do nothing.
    }

    override fun onPermissionsDenied(requestCode: Int, list: List<String>) {
        // Do nothing.
    }

    private fun acquireGooglePlayServices() {
        val apiAvailability = GoogleApiAvailability.getInstance()
        val connectionStatusCode = apiAvailability.isGooglePlayServicesAvailable(this)
        if (apiAvailability.isUserResolvableError(connectionStatusCode)) {
            showGooglePlayServicesAvailabilityErrorDialog(connectionStatusCode)
        }
    }

    private fun showGooglePlayServicesAvailabilityErrorDialog(
            connectionStatusCode: Int) {
        val apiAvailability = GoogleApiAvailability.getInstance()
        val dialog = apiAvailability.getErrorDialog(
                this,
                connectionStatusCode,
                REQUEST_GOOGLE_PLAY_SERVICES)
        dialog.show()
    }
}
