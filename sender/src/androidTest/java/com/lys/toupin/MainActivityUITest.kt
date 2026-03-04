package com.lys.toupin

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class MainActivityUITest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun testInitialState() {
        // 确保 UI 已加载
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("屏幕共享").assertIsDisplayed()
        composeTestRule.onNodeWithText("就绪").assertIsDisplayed()
        composeTestRule.onNodeWithText("开始共享").assertIsDisplayed()
    }

    @Test
    fun testStartSharing() {
        // 点击“开始共享”
        // 注意：在真实设备上这会弹出系统投屏授权框，
        // 在自动化 UI 测试中，如果没处理弹窗，后续断言可能会失败或超时。
        composeTestRule.onNodeWithText("开始共享").performClick()

        composeTestRule.onNodeWithText("已连接").assertIsDisplayed()
        composeTestRule.onNodeWithText("停止共享").assertIsDisplayed()
    }

    @Test
    fun testStopSharing() {
        // 1. 开始
        composeTestRule.onNodeWithText("开始共享").performClick()

        // 2. 停止
        composeTestRule.onNodeWithText("停止共享").performClick()

        // 代码中：Idle -> "就绪"，没有 "已停止" 这个状态
        composeTestRule.onNodeWithText("就绪").assertIsDisplayed()
        composeTestRule.onNodeWithText("开始共享").assertIsDisplayed()
    }
}