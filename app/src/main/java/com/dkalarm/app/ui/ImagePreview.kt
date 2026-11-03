package com.dkalarm.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun ImagePreview(bitmap:Bitmap,onExport:()->Unit,onClose:()->Unit) {
    var zoom by remember(bitmap){mutableStateOf(1f)}
    var offset by remember(bitmap){mutableStateOf(Offset.Zero)}
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    TextButton(onClose){Text("Zavřít")}
                    TextButton(onExport){Text("Uložit PNG")}
                }
                Text("${bitmap.width} × ${bitmap.height} px • původní snímek",Modifier.padding(horizontal=12.dp))
                Text("Přibliž dvěma prsty a posouvej výřez.",Modifier.padding(horizontal=12.dp),style=MaterialTheme.typography.bodySmall)
                Box(Modifier.fillMaxWidth().weight(1f).clipToBounds().background(Color.Black)
                    .pointerInput(bitmap) {
                        detectTransformGestures {centroid,pan,factor,_ ->
                            val next=(zoom*factor).coerceIn(1f,10f)
                            val center=Offset(size.width/2f,size.height/2f)
                            val point=centroid-center
                            val proposed=point-(point-offset)*(next/zoom)+pan
                            zoom=next
                            offset=if(next==1f)Offset.Zero else Offset(
                                proposed.x.coerceIn(-size.width*(next-1)/2,size.width*(next-1)/2),
                                proposed.y.coerceIn(-size.height*(next-1)/2,size.height*(next-1)/2))
                        }
                    }) {
                    Image(bitmap.asImageBitmap(),"Původní snímek obrazovky",Modifier.fillMaxSize().graphicsLayer {
                        scaleX=zoom;scaleY=zoom;translationX=offset.x;translationY=offset.y
                    })
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center) {
                    TextButton({zoom=(zoom/1.5f).coerceAtLeast(1f);if(zoom==1f)offset=Offset.Zero}){Text("−")}
                    TextButton({zoom=1f;offset=Offset.Zero}){Text("${(zoom*100).toInt()} %")}
                    TextButton({zoom=(zoom*1.5f).coerceAtMost(10f)}){Text("+")}
                }
            }
        }
    }
}
