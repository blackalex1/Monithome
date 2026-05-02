package com.monithome.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monithome.network.SocketManager

@Composable
fun LoginScreen(onConnect: (String) -> Unit) {
    var ipAddress by remember { mutableStateOf("") }
    val isConnecting by SocketManager.isConnecting.collectAsState()
    val error by SocketManager.error.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedBackground()
        
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            GlassCard(
                modifier = Modifier.fillMaxWidth(0.85f),
                cornerRadius = 32.dp
            ) {
                Column(
                    modifier = Modifier.padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .background(MonitTheme.Primary.copy(alpha = 0.1f), RoundedCornerShape(24.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Computer,
                            contentDescription = null,
                            tint = MonitTheme.Primary,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Text(
                        "MONITHOME",
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 4.sp
                    )
                    Text(
                        "ENTER HOST IP ADDRESS",
                        color = MonitTheme.TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    
                    Spacer(modifier = Modifier.height(40.dp))
                    
                    OutlinedTextField(
                        value = ipAddress,
                        onValueChange = { 
                            ipAddress = it 
                            if (error != null) SocketManager.clearError()
                        },
                        label = { Text("IP ADDRESS", fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = MonitTheme.Primary,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                            focusedLabelColor = MonitTheme.Primary,
                            unfocusedLabelColor = MonitTheme.TextSecondary
                        ),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { if (!isConnecting) onConnect(ipAddress) }
                        ),
                        singleLine = true,
                        isError = error != null && !error!!.contains("код")
                    )
                    
                    if (error != null && !error!!.contains("код")) {
                        Text(
                            error!!,
                            color = Color(0xFFFB7185),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(32.dp))
                    
                    Button(
                        onClick = { onConnect(ipAddress) },
                        enabled = !isConnecting,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MonitTheme.Primary,
                            disabledContainerColor = MonitTheme.Primary.copy(alpha = 0.3f)
                        ),
                        shape = RoundedCornerShape(16.dp),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp)
                    ) {
                        if (isConnecting) {
                            CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(24.dp))
                        } else {
                            Text("CONNECT", color = Color.Black, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PairingScreen(onPair: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    val isConnecting by SocketManager.isConnecting.collectAsState()
    val error by SocketManager.error.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedBackground()
        
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            GlassCard(
                modifier = Modifier.fillMaxWidth(0.85f),
                cornerRadius = 32.dp
            ) {
                Column(
                    modifier = Modifier.padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .background(Color(0xFFF59E0B).copy(alpha = 0.1f), RoundedCornerShape(24.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Link,
                            contentDescription = null,
                            tint = Color(0xFFF59E0B),
                            modifier = Modifier.size(40.dp)
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Text(
                        "PAIRING",
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 4.sp
                    )
                    Text(
                        "ENTER THE 6-DIGIT CODE",
                        color = MonitTheme.TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(top = 4.dp),
                        textAlign = TextAlign.Center
                    )
                    
                    Spacer(modifier = Modifier.height(40.dp))
                    
                    OutlinedTextField(
                        value = code,
                        onValueChange = { 
                            if (it.length <= 6) {
                                code = it 
                                if (error != null) SocketManager.clearError()
                            }
                        },
                        label = { Text("6-DIGIT CODE", fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFFF59E0B),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                            focusedLabelColor = Color(0xFFF59E0B),
                            unfocusedLabelColor = MonitTheme.TextSecondary,
                            errorBorderColor = Color(0xFFFB7185)
                        ),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { if (!isConnecting && code.length == 6) onPair(code) }
                        ),
                        singleLine = true,
                        isError = error != null,
                        textStyle = LocalTextStyle.current.copy(
                            textAlign = TextAlign.Center, 
                            fontSize = 32.sp, 
                            letterSpacing = 12.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.White
                        )
                    )

                    if (error != null) {
                        Text(
                            error!!,
                            color = Color(0xFFFB7185),
                            fontSize = 14.sp,
                            modifier = Modifier.padding(top = 16.dp),
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(32.dp))
                    
                    Button(
                        onClick = { onPair(code) },
                        enabled = !isConnecting && code.length == 6,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFF59E0B),
                            disabledContainerColor = Color(0xFFF59E0B).copy(alpha = 0.3f)
                        ),
                        shape = RoundedCornerShape(16.dp),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp)
                    ) {
                        if (isConnecting) {
                            CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(24.dp))
                        } else {
                            Text("CONFIRM", color = Color.Black, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                        }
                    }
                }
            }
        }
    }
}
