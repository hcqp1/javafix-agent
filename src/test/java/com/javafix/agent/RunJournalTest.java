package com.javafix.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RunJournalTest {

    @TempDir
    Path directory;

    @Test
    void keepsCompletedStepsOnDiskBeforeTheRunEnds() throws Exception {
        RunJournal journal = RunJournal.create(directory, directory.resolve("project"), "修复加法错误");

        journal.appendStep("[复现] 步骤 1\n观察：失败测试 CalculatorTest");

        assertTrue(Files.exists(journal.path()));
        List<String> lines = Files.readAllLines(journal.path());
        assertEquals(2, lines.size(), "开始记录和已完成步骤都应立即落盘");
        JsonNode step = new ObjectMapper().readTree(lines.get(1));
        assertEquals("step", step.get("type").asText());
        assertTrue(step.get("entry").asText().contains("CalculatorTest"));
    }

    @Test
    void writesAnEndMarkerOnlyWhenTheRunFinishes() throws Exception {
        RunJournal interrupted = RunJournal.create(directory, directory, "中断的任务");
        interrupted.appendStep("已完成一步");
        assertEquals(2, Files.readAllLines(interrupted.path()).size());

        RunJournal returned = RunJournal.create(directory, directory, "正常返回的任务");
        returned.finish("returned");
        List<String> lines = Files.readAllLines(returned.path());
        JsonNode end = new ObjectMapper().readTree(lines.get(lines.size() - 1));
        assertEquals("finish", end.get("type").asText());
        assertEquals("returned", end.get("status").asText());
    }

    @Test
    void createsPrivateRunsDirectoryOnPosix() throws Exception {
        assumeTrue(Files.getFileAttributeView(directory, PosixFileAttributeView.class) != null);

        Path runs = directory.resolve("javafix").resolve("runs");
        RunJournal.create(runs, directory, "修复加法错误");

        assertEquals(PosixFilePermissions.fromString("rwx------"),
                Files.getPosixFilePermissions(runs));
    }

    @Test
    void createsOwnerOnlyJournalOnPosix() throws Exception {
        assumeTrue(Files.getFileAttributeView(directory, PosixFileAttributeView.class) != null);

        RunJournal journal = RunJournal.create(directory.resolve("runs"), directory, "修复加法错误");

        assertEquals(PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(journal.path()));
    }
}
