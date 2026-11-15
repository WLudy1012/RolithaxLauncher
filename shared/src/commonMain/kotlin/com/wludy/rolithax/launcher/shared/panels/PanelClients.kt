package com.wludy.rolithax.launcher.shared.panels

import io.ktor.client.HttpClient

fun createPanelClient(profile: PanelProfile, http: HttpClient): PanelClient = when (profile.kind) {
    PanelKind.MCSL -> McslPanelClient(profile, http)
    PanelKind.MCSM -> McsmPanelClient(profile, http)
    PanelKind.PTERODACTYL -> PterodactylPanelClient(profile, http)
}
