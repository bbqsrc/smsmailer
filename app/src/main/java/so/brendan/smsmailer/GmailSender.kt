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

import android.os.AsyncTask
import android.util.Log
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.services.gmail.Gmail
import com.google.api.services.gmail.model.Message
import javax.mail.internet.MimeMessage

sealed class EmailTaskResult

data class EmailError(val e: UserRecoverableAuthException): EmailTaskResult()
data class EmailMessage(val message: Message): EmailTaskResult()

class GmailSender(val credential: GoogleAccountCredential, appName: String) {
    companion object {
        private val TAG: String = this::class.java.name
    }

    private val service: Gmail

    init {
        val transport = AndroidHttp.newCompatibleTransport()
        val jsonFactory = JacksonFactory.getDefaultInstance()
        service = Gmail.Builder(
                transport, jsonFactory, credential)
                .setApplicationName(appName)
                .build()
    }

    inner class InsertEmailTask(private val callback: (EmailTaskResult) -> Unit = {}): AsyncTask<MimeMessage, Void, EmailTaskResult>() {
        override fun doInBackground(vararg params: MimeMessage): EmailTaskResult {
            val mimeMessage = params[0]

            val message = Message()
            message.raw = mimeMessage.toBase64String()
            message.labelIds = listOf("INBOX", "UNREAD")

            val res = try {
                service.users().messages().insert("me", message).execute()
            } catch (e: UserRecoverableAuthIOException) {
                Log.e(TAG, "Exception type: ${e::class.java.canonicalName}")
                return EmailError(e.cause!!)

            }

            Log.i(TAG, "Email sent: ${res.id}")
            Log.d(TAG, res.toPrettyString())

            return EmailMessage(res)
        }

        override fun onPostExecute(result: EmailTaskResult) {
            super.onPostExecute(result)

            callback(result)
        }
    }

    fun insertEmail(mimeMessage: MimeMessage, callback: (EmailTaskResult) -> Unit = {}) {
        InsertEmailTask(callback).execute(mimeMessage)
    }
}