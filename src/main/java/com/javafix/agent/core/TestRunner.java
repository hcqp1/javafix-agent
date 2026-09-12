package com.javafix.agent.core;

import java.nio.file.Path;

public interface TestRunner {

    TestResult run(Path projectPath);

}