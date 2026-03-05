package io.karpfen

interface Channel {

    fun push(clientId: String, message: String)

}