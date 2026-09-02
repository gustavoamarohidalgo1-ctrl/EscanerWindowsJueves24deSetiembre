package com.facturastock.app.feature.preview

/** Test tags estables de la vista previa de páginas para las pruebas de Compose. */
object PreviewTestTags {
    const val SCREEN = "preview_screen"
    const val PAGE_IMAGE = "preview_page_image"
    const val PAGE_INDICATOR = "preview_page_indicator"
    const val PREVIOUS_PAGE = "preview_previous_page"
    const val NEXT_PAGE = "preview_next_page"
    const val ACTION_RETAKE = "preview_action_retake"
    const val ACTION_ROTATE = "preview_action_rotate"
    const val ACTION_CROP = "preview_action_crop"
    const val ACTION_ADD_PAGE = "preview_action_add_page"
    const val ACTION_MOVE_UP = "preview_action_move_up"
    const val ACTION_MOVE_DOWN = "preview_action_move_down"
    const val ACTION_DELETE = "preview_action_delete"
    const val ACTION_PROCESS = "preview_action_process"
    const val DELETE_DIALOG = "preview_delete_dialog"
    const val CROP_OVERLAY = "preview_crop_overlay"
    const val CROP_APPLY = "preview_crop_apply"
    const val CROP_CANCEL = "preview_crop_cancel"

    /** Tag del tirador de una esquina del recorte: `preview_crop_handle_<corner>`. */
    fun cropHandle(corner: PreviewContract.CropCorner): String =
        "preview_crop_handle_${corner.name.lowercase()}"
}
