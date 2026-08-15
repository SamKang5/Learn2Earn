package com.example.learn2earn2.emergency

object EmergencyServices {
    val officialServices = listOf(
        EmergencyCallTarget("Police", "113", official = true),
        EmergencyCallTarget("Fire and Rescue", "114", official = true),
        EmergencyCallTarget("Medical Emergency", "115", official = true)
    )
}
