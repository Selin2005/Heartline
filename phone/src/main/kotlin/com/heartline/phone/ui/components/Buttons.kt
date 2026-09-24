package com.heartline.phone.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.heartline.phone.ui.theme.Dimens
import com.heartline.phone.ui.theme.HeartlineTheme

@Composable
fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = HeartlineTheme.colors.primary) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
        modifier = modifier.fillMaxWidth().height(Dimens.buttonHeight),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun TonalPillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = HeartlineTheme.colors.primary) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(containerColor = HeartlineTheme.colors.surfaceVariant, contentColor = color),
        modifier = modifier.fillMaxWidth().height(Dimens.buttonHeight),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun LinkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = HeartlineTheme.colors.primary) {
    TextButton(onClick = onClick, modifier = modifier) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = color)
    }
}
