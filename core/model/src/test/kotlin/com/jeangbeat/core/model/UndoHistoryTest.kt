package com.jeangbeat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UndoHistoryTest {
    @Test
    fun undoAndRedo() {
        val h = UndoHistory(0)
        h.push(1)
        h.push(2)
        assertEquals(1, h.undo())
        assertEquals(0, h.undo())
        assertFalse(h.canUndo)
        assertEquals(1, h.redo())
        assertEquals(2, h.redo())
        assertFalse(h.canRedo)
    }

    @Test
    fun pushingClearsRedo() {
        val h = UndoHistory(0)
        h.push(1)
        h.undo()
        h.push(5)
        assertFalse(h.canRedo)
        assertEquals(0, h.undo())
    }

    @Test
    fun identicalStateIsNotRecorded() {
        val h = UndoHistory(0)
        h.push(0)
        assertFalse(h.canUndo)
    }

    @Test
    fun limitDropsOldestSteps() {
        val h = UndoHistory(0, limit = 3)
        (1..10).forEach { h.push(it) }
        var undos = 0
        while (h.canUndo) {
            h.undo()
            undos++
        }
        assertEquals(3, undos)
        assertEquals(7, h.present)
    }

    @Test
    fun replaceDoesNotCreateUndoStep() {
        val h = UndoHistory(0)
        h.replace(9)
        assertFalse(h.canUndo)
        assertEquals(9, h.present)
        h.push(10)
        assertTrue(h.canUndo)
        assertEquals(9, h.undo())
    }
}
