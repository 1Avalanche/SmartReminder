package com.anastasiyaa.smartreminder

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform