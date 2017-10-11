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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.util.Base64
import com.google.api.client.util.ExponentialBackOff
import pub.devrel.easypermissions.EasyPermissions
import java.io.ByteArrayOutputStream
import java.util.*
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

fun String.emailWithPlus(value: String): String {
    val chunks = this.split("@")
    val clean = value
            .replace(Regex("^\\+*"), "")
            .replace(" ", "")
            .replace(Regex("[^0-9a-zA-Z]"), "-")
    return "${chunks[0]}+sms-${clean}@${chunks[1]}"
}

fun MimeMessage.toBase64String(): String {
    val os = ByteArrayOutputStream()
    writeTo(os)
    return Base64.encodeBase64URLSafeString(os.toByteArray())
}

fun SmsMessage.toMimeMessage(emailTo: String, subjectTmpl: String): MimeMessage {
    val from = displayOriginatingAddress
    val body = displayMessageBody

    var msg = MimeMessage(Session.getDefaultInstance(Properties()))
    msg.setFrom(InternetAddress(emailTo.emailWithPlus(from), "$from (SMS)"))
    msg.addRecipient(javax.mail.Message.RecipientType.TO, InternetAddress(emailTo))
    msg.subject = String.format(subjectTmpl, from)
    msg.setText(body)

    return msg
}

class SmsReceiver: BroadcastReceiver() {
    private val TAG = this::class.java.name

    companion object {
        var sender: GmailSender? = null
    }

    private fun getCredential(context: Context): GoogleAccountCredential? {
        if (EasyPermissions.hasPermissions(context, Manifest.permission.GET_ACCOUNTS)) {
            val prefs = context.getSharedPreferences(PREF_ID, MODE_PRIVATE)
            val accountName = prefs.getString(PREF_ACCOUNT_NAME, null)

            if (accountName != null) {
                val credential = GoogleAccountCredential
                        .usingOAuth2(context.applicationContext, SCOPES)
                        .setBackOff(ExponentialBackOff())
                credential.selectedAccountName = accountName
                return credential
            }
        }

        return null
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        Log.d(TAG, "Receiver triggered")

        if (intent == null || context == null) {
            return
        }

        if (sender == null) {
            val credential = getCredential(context) ?: return
            sender = GmailSender(credential, context.getString(R.string.app_name))
        }

        sender?.let { s ->
            val subjectTmpl = context.resources.getString(R.string.email_subject)
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            val emails = messages.map {
                it.toMimeMessage(
                    emailTo = s.credential.selectedAccountName,
                    subjectTmpl = subjectTmpl
                )
            }
            emails.forEach { s.insertEmail(it) }
        }
    }
}