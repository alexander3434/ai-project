package com.aiturbo.plugins

import io.ktor.server.application.Application
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.util.AttributeKey
import org.kodein.di.DI

private val DiAttribute = AttributeKey<DI>("aiturbo.di")

/** Stores the container on the application; nothing is resolved here. */
fun Application.installDi(di: DI) {
    attributes.put(DiAttribute, di)
}

/** The running application's container (routes resolve lazily from it). */
val Application.di: DI get() = attributes[DiAttribute]

/** The container of the application the route is registered on. */
val Route.di: DI get() = application.di
