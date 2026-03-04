package com.lys.toupin

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreenShareViewModelTest {

    private lateinit var viewModel: ScreenShareViewModel
    private lateinit var context: Context

    @Before
    fun setUp() {
        viewModel = ScreenShareViewModel()
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @Test
    fun initialState_isIdle() = runBlocking {
        val state = viewModel.state.first()
        Assert.assertEquals(ScreenShareState.Idle, state)
    }

    @Test
    fun startSharing_transitionsToConnected() = runBlocking {
        // 初始状态
        Assert.assertEquals(ScreenShareState.Idle, viewModel.state.first())

        // 开始共享
        viewModel.startSharing(context)
        val state = viewModel.state.first()
        Assert.assertEquals(ScreenShareState.Connected, state)
    }

    @Test
    fun stopSharing_transitionsToIdle() = runBlocking {
        // 先开始共享
        viewModel.startSharing(context)
        Assert.assertEquals(ScreenShareState.Connected, viewModel.state.first())

        // 停止共享
        viewModel.stopSharing(context)
        val state = viewModel.state.first()
        Assert.assertEquals(ScreenShareState.Idle, state)
    }

    @Test
    fun simulateError_transitionsToErrorState() = runBlocking {
        // 模拟错误
        viewModel.simulateError()
        val state = viewModel.state.first()
        assert(state is ScreenShareState.Error)
        Assert.assertEquals("连接失败，请重试", (state as ScreenShareState.Error).message)
    }

    @Test
    fun resetError_transitionsToIdle() = runBlocking {
        // 先模拟错误
        viewModel.simulateError()
        assert(viewModel.state.first() is ScreenShareState.Error)

        // 重置错误
        viewModel.resetError()
        val state = viewModel.state.first()
        Assert.assertEquals(ScreenShareState.Idle, state)
    }

    @Test
    fun stateTransitions_correctSequence() = runBlocking {
        // 1. 初始状态
        Assert.assertEquals(ScreenShareState.Idle, viewModel.state.first())

        // 2. 开始共享
        viewModel.startSharing(context)
        Assert.assertEquals(ScreenShareState.Connected, viewModel.state.first())

        // 3. 停止共享
        viewModel.stopSharing(context)
        Assert.assertEquals(ScreenShareState.Idle, viewModel.state.first())

        // 4. 模拟错误
        viewModel.simulateError()
        assert(viewModel.state.first() is ScreenShareState.Error)

        // 5. 重置错误
        viewModel.resetError()
        Assert.assertEquals(ScreenShareState.Idle, viewModel.state.first())

        // 6. 再次开始共享
        viewModel.startSharing(context)
        Assert.assertEquals(ScreenShareState.Connected, viewModel.state.first())
    }
}