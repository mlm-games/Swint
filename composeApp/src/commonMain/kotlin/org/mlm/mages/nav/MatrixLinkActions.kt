package org.mlm.mages.nav

import org.mlm.mages.MatrixService

// Call from UI or intent handler
suspend fun handleMatrixLink(
    service: MatrixService,
    link: MatrixLink,
    openRoom: (roomId: String, title: String) -> Unit
): Boolean {
    return when (link) {
        is MatrixLink.User -> {
            val rid = service.port.ensureDm(link.mxid) ?: return false
            openRoom(rid, link.mxid)
            true
        }
        is MatrixLink.Room -> {
            val target = link.target.roomIdOrAlias
            val ok = service.port.joinByIdOrAlias(target)
            if (!ok) return false
            // We joined; use roomId if known = target (when it is !id) or alias text otherwise
            openRoom(target, target)
            true
        }
        MatrixLink.Unsupported -> false
    }
}