/*
 * ------------------------------------------------------------
 * "THE BEERWARE LICENSE" (Revision 42):
 * Pavel Matusevich <neitex@protonmail.com> wrote this code.
 * As long as you retain this notice, you can do whatever you
 * want with this stuff. If we meet someday, and you think this
 * stuff is worth it, you can buy me a beer (or cider) in return.
 * Or drink it yourself in my honor.
 * ------------------------------------------------------------
 */

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.java.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.*
import org.w3c.dom.NodeList
import java.io.File
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Clock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.X509TrustManager
import javax.xml.parsers.DocumentBuilderFactory

data class Person(
    val lastName: String,
    val firstName: String,
    val middleName: String,
    val parallel: Int,
    val letter: String,
    val cardID: String
) {
    val cardIDNumber: Long
        get() = cardID.toLong(radix = 16)
    val fullName = "$lastName $firstName $middleName".replace("ё", "е")
    val toCSV = "$lastName,$firstName,$middleName,$parallel,$letter,$cardID"
}

data class RusguardPerson(
    val uuid: String,
    val lastName: String,
    val firstName: String?,
    val middleName: String?,
) {
    val fullName = "$lastName $firstName $middleName".replace("ё", "е")
}

class TrustAllX509TrustManager : X509TrustManager {
    override fun getAcceptedIssuers(): Array<X509Certificate?> = arrayOfNulls(0)

    override fun checkClientTrusted(certs: Array<X509Certificate?>?, authType: String?) {}

    override fun checkServerTrusted(certs: Array<X509Certificate?>?, authType: String?) {}
}

fun NodeList.toList() = buildList(length) {
    for (i in 0 until length) {
        add(item(i))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
fun main() {
    println("Please, enter your server address (including \"LNetwork...\") and hit Enter:")
    val rusguardURL = readlnOrNull()
    checkNotNull(rusguardURL) { "Server address is not entered" }
    println("Using server address: $rusguardURL")
    val importFile = File("applications.csv")
    check(importFile.exists()) { "File ${importFile.absolutePath} does not exist" }
    val persons = importFile.readLines()
        .map { it.split(",") }
        .map { Person(it[0], it[1], it[2], it[3].toInt(), it[4], it[5]) }
    val badPersonsFile = File("failed-persons-application.csv")
    badPersonsFile.createNewFile()
    badPersonsFile.writeText("Фамилия,Имя,Отчество,Параллель,Буква,Карта\n")
    run {
        val distinct = persons.distinctBy { it.fullName }
        if (distinct.size != persons.size) {
            println("Collisions found (applications)")
            persons.groupBy { it.fullName }
                .filter { it.value.size > 1 }
                .forEach {
                    badPersonsFile.appendText(it.value.joinToString("\n", postfix = "\n") { it.toCSV })
                    println(it)
                }
        }
    }
    val personsMap = persons.distinctBy { it.fullName }.associateBy { it.fullName }
    val requestTemplate = File("request-template.xml")
    check(requestTemplate.exists()) { "File ${requestTemplate.absolutePath} does not exist" }
    val requestTemplateText = requestTemplate.readText()
    val usersList = File("users-list.xml")
    check(usersList.exists()) { "File ${usersList.absolutePath} does not exist" }
    val rusGuardUsers = run {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(usersList)
        doc.documentElement.normalize()
        doc.getElementsByTagName("a:AcsEmployeeSlim").toList().mapNotNull {
            val nodes = it.childNodes.toList()
//            if (nodes.find { it.nodeName == "a:AccessLevels" }?.childNodes?.toList()?.component2()?.childNodes?.toList()
//                    ?.find { it.nodeName == "b:Name" }?.textContent?.contains("учащиеся") != true
//            )
//                return@mapNotNull null
            RusguardPerson(
                nodes.find { it.nodeName == "a:ID" }!!.textContent,
                nodes.find { it.nodeName == "a:LastName" }!!.textContent,
                nodes.find { it.nodeName == "a:FirstName" }?.textContent,
                nodes.find { it.nodeName == "a:SecondName" }?.textContent
            )
        }
    }
    val badRusguardPersonsFile = File("failed-persons-rusguard.csv")
    badRusguardPersonsFile.createNewFile()
    badRusguardPersonsFile.writeText("UUID,Фамилия,Имя,Отчество\n")
    run {
        val distinct = rusGuardUsers.distinctBy { it.fullName }
        if (distinct.size != rusGuardUsers.size) {
            println("Collisions found (rusguard)")
            rusGuardUsers.groupBy { it.fullName }
                .filter { it.value.size > 1 }
                .forEach {
                    badRusguardPersonsFile.appendText(it.value.joinToString("\n", postfix = "\n") {
                        "${it.uuid},${it.lastName},${it.firstName},${it.middleName}"
                    })
                    println(it)
                }
        }
    }
    val rusGuardMapped = rusGuardUsers.distinctBy { it.fullName }.associateWith { personsMap[it.fullName] }.filter {
        if (it.value == null) {
            println("No person found for ${it.key}")
            badRusguardPersonsFile.appendText("${it.key.uuid},${it.key.lastName},${it.key.firstName},${it.key.middleName}\n")
        }
        it.value != null
    }.map { it.key to it.value!! }.toMap()
    println("Ready to add cards to ${rusGuardMapped.size} users. Continue? [y/n]")
    val answer = readlnOrNull()
    if (answer?.lowercase() != "y") {
        println("Aborted")
        return
    }
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
    val client = HttpClient(Java) {
        expectSuccess = false
        engine {
            this.config {
                this.sslParameters(SSLParameters().apply {
                    needClientAuth = false
                })
                this.sslContext(SSLContext.getInstance("TLS").apply {
                    init(arrayOf(), arrayOf(TrustAllX509TrustManager()), SecureRandom())
                })
            }
        }
    }
    val failedRequestsFile = File("failed-requests.csv")
    failedRequestsFile.createNewFile()
    failedRequestsFile.writeText("UUID,Фамилия,Имя,Отчество,Код\n")
    val scope = CoroutineScope(Dispatchers.IO.limitedParallelism(5))
    rusGuardMapped.toList().chunked(5).forEach { list ->
        list.forEach { (rusGuardUser, person) ->
            scope.launch {
                val date = LocalDateTime.now(Clock.systemUTC())
                println(date.format(formatter))
                val request = requestTemplateText
                    .replace("{dateCreated}", date.format(formatter))
                    .replace("{dateExpires}", date.plusMinutes(5).format(formatter))
                    .replace("{employeeID}", rusGuardUser.uuid)
                    .replace("{keyNumber}", person.cardIDNumber.toString())
                val response = client.post(rusguardURL) {
                    setBody(request)
                    header(HttpHeaders.ContentType, ContentType.Text.Xml)
                    header(
                        "SOAPAction",
                        "\"http://www.rusguardsecurity.ru/ILNetworkConfigurationService/AssignAcsKeyForEmployee\""
                    )
                    header(HttpHeaders.Expect, "100-continue")
                }
                if (response.status != HttpStatusCode.OK) {
                    println("Error adding card for ${rusGuardUser.fullName} (${response.body<String>()})")
                    failedRequestsFile.appendText("${rusGuardUser.uuid},${rusGuardUser.lastName},${rusGuardUser.firstName},${rusGuardUser.middleName},${response.status.value}\n")
                } else {
                    println("Card added for ${rusGuardUser.fullName}")
                }
            }
        }
        runBlocking {
            println("Batch sent;")
            delay(1000)
        }
    }
    println("Done!")
    println("Script made by Neitex (https://gitub.com/Neitex) for your convenience :)")
    println("If this script helped you, please consider sharing it with your acquaintances, maybe it will help them too!")
    println("If you have any questions, please contact me via Telegram: @Neitex")
    println("-----------------------------------------------------------------------------")
    println("Скрипт сделан Neitex (https://gitub.com/Neitex) для вашего удобства :)")
    println("Если этот скрипт помог вам, пожалуйста, поделитесь им с вашими знакомыми-администраторами, вдруг им тоже пригодится!")
    println("Если у вас есть какие-либо вопросы, пожалуйста, свяжитесь со мной через Telegram: @Neitex")
}
