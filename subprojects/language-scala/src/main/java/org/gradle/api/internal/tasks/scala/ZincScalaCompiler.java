/*
 * Copyright 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.tasks.scala;

import com.google.common.collect.Iterables;
import org.gradle.api.internal.tasks.compile.CompilationFailedException;
import org.gradle.api.internal.tasks.compile.JavaCompilerArgumentsBuilder;
import org.gradle.api.internal.tasks.compile.JdkTools;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.tasks.WorkResult;
import org.gradle.api.tasks.WorkResults;
import org.gradle.internal.time.Time;
import org.gradle.internal.time.Timer;
import org.gradle.language.base.internal.compile.Compiler;
import org.gradle.util.GFileUtils;
import sbt.internal.inc.Locate;
import sbt.internal.inc.LoggedReporter;
import sbt.internal.inc.ScalaInstance;
import sbt.internal.inc.ZincUtil;
import sbt.internal.inc.javac.LocalJavaCompiler;
import scala.Option;
import xsbti.ArtifactInfo;
import xsbti.T2;
import xsbti.compile.*;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public class ZincScalaCompiler implements Compiler<ScalaJavaJointCompileSpec>, Serializable {
    private static final Logger LOGGER = Logging.getLogger(ZincScalaCompiler.class);
    private final Iterable<File> scalaClasspath;
    private Iterable<File> zincClasspath;
    private final File gradleUserHome;
    private static final Map<File, ClassLoaders> CLASSLOADER_CACHE = new ConcurrentHashMap<>();

    public ZincScalaCompiler(Iterable<File> scalaClasspath, Iterable<File> zincClasspath, File gradleUserHome) {
        this.scalaClasspath = scalaClasspath;
        this.zincClasspath = zincClasspath;
        this.gradleUserHome = gradleUserHome;
    }

    @Override
    public WorkResult execute(ScalaJavaJointCompileSpec spec) {
        return Compiler.execute(scalaClasspath, zincClasspath, gradleUserHome, spec);
    }

    // need to defer loading of Zinc/sbt/Scala classes until we are
    // running in the compiler daemon and have them on the class path
    private static class Compiler {
        static WorkResult execute(final Iterable<File> scalaClasspath, final Iterable<File> zincClasspath, File gradleUserHome, final ScalaJavaJointCompileSpec spec) {
            LOGGER.info("Compiling with Zinc Scala compiler.");

            final String userSuppliedZincDir = System.getProperty("zinc.dir");
            if (userSuppliedZincDir != null) {
                LOGGER.warn(ZincScalaCompilerUtil.ZINC_DIR_IGNORED_MESSAGE);
            }

            final xsbti.Logger logger = new SbtLoggerAdapter();

            Timer timer = Time.startTimer();

            IncrementalCompiler compiler = ZincCompilerUtil.defaultIncrementalCompiler();
            List<String> scalacOptions = new ZincScalaCompilerArgumentsGenerator().generate(spec);
            List<String> javacOptions = new JavaCompilerArgumentsBuilder(spec).includeClasspath(false).noEmptySourcePath().build();

            CompileOptions compileOptions = CompileOptions.create()
                .withSources(Iterables.toArray(spec.getSourceFiles(), File.class))
                .withClasspath(Iterables.toArray(Iterables.concat(scalaClasspath, spec.getCompileClasspath()), File.class))
                .withScalacOptions(scalacOptions.toArray(new String[scalacOptions.size()]))
                .withClassesDirectory(spec.getDestinationDir())
                .withJavacOptions(javacOptions.toArray(new String[javacOptions.size()]));


            PerClasspathEntryLookup lookup = new PerClasspathEntryLookup() {
                @Override
                public Optional<CompileAnalysis> analysis(File classpathEntry) {
                    Optional<File> file = Optional.ofNullable(spec.getAnalysisMap().get(classpathEntry));
                    return file.flatMap(f->FileAnalysisStore.getDefault(f).get()).map(s -> s.getAnalysis());
                }

                @Override
                public DefinesClass definesClass(File classpathEntry) {
                    return Locate.definesClass(classpathEntry);
                }
            };

            File analysisFile = spec.getAnalysisFile();
            AnalysisStore storePast = FileAnalysisStore.getDefault(analysisFile);
            AnalysisStore storeNext = AnalysisStore.getCachedStore(storePast);

            PreviousResult previousResult = storePast.get()
                .map(a -> PreviousResult.of(Optional.of(a.getAnalysis()), Optional.of(a.getMiniSetup())))
                .orElse(PreviousResult.of(Optional.empty(), Optional.empty()));

            File libraryJar = findFile(ArtifactInfo.ScalaLibraryID, scalaClasspath);
            File compilerJar = findFile(ArtifactInfo.ScalaCompilerID, zincClasspath);

            ClassLoaders classLoaders = CLASSLOADER_CACHE.computeIfAbsent(libraryJar, f ->
                new ClassLoaders(getClassLoader(scalaClasspath), getClassLoader(Collections.singleton(libraryJar))));
            String scalaVersion = getScalaVersion(classLoaders.scalaClassLoader);
            File[] allJars = Iterables.toArray(scalaClasspath, File.class);
            ScalaInstance scalaInstance = new ScalaInstance(scalaVersion,
                classLoaders.scalaClassLoader,
                classLoaders.libraryOnlyClassLoader,
                libraryJar,
                compilerJar,
                allJars,
                Option.empty());

            File bridgeJar = findFile(ZincUtil.getDefaultBridgeModule(scalaVersion).name(), zincClasspath);
            ScalaCompiler scalaCompiler = ZincCompilerUtil.scalaCompiler(scalaInstance, bridgeJar, ClasspathOptionsUtil.auto());
            Compilers compilers = Compilers.create(scalaCompiler, new GradleJavaTool());
            GlobalsCache cache = CompilerCache.getDefault();
            Setup setup = Setup.create(lookup,
                false,
                analysisFile,
                cache,
                IncOptions.of(),
                new LoggedReporter(100, logger, p -> p),
                Optional.empty(),
                getExtra()
            );

            Inputs inputs = Inputs.create(compilers, compileOptions, setup, previousResult);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(inputs.toString());
            }
            if (spec.getScalaCompileOptions().isForce()) {
                GFileUtils.deleteDirectory(spec.getDestinationDir());
            }
            LOGGER.info("Prepared Zinc Scala inputs: {}", timer.getElapsed());

            try {
                CompileResult compile = compiler.compile(inputs, logger);
                AnalysisContents contentNext = AnalysisContents.create(compile.analysis(), compile.setup());
                storeNext.set(contentNext);
            } catch (xsbti.CompileFailed e) {
                throw new CompilationFailedException(e);
            }
            LOGGER.info("Completed Scala compilation: {}", timer.getElapsed());

            return WorkResults.didWork(true);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private static T2<String, String>[] getExtra() {
            return new T2[0];
        }

        private static String getScalaVersion(ClassLoader scalaClassLoader) {
            try {
                Properties props = new Properties();
                props.load(scalaClassLoader.getResource("library.properties").openStream());
                return props.getProperty("version.number");
            } catch (IOException e) {
                throw new IllegalStateException("Unable to determin scala version");
            }

        }

        private static File findFile(String id, Iterable<File> files) {
            try {
                return Iterables.find(files, f -> f.getName().contains(id));
            } catch (NoSuchElementException e) {
                throw new IllegalStateException("Cannot find " + id + " in " + files);
            }

        }

        private static ClassLoader getClassLoader(Iterable<File> classpath) {

            try {
                List<URL> urls = new ArrayList<>();
                for (File file : classpath) {
                    urls.add(file.toURI().toURL());
                }
                return new URLClassLoader(urls.toArray(new URL[urls.size()]));
            } catch (MalformedURLException e) {
                throw new IllegalStateException(e);
            }
        }

    }

    private static class ClassLoaders {
        ClassLoader scalaClassLoader;
        ClassLoader libraryOnlyClassLoader;

        public ClassLoaders(ClassLoader scalaClassLoader, ClassLoader libraryOnlyClassLoader) {
            this.scalaClassLoader = scalaClassLoader;
            this.libraryOnlyClassLoader = libraryOnlyClassLoader;
        }
    }

    private static class GradleJavaTool implements JavaTools {

        @Override
        public JavaCompiler javac() {

            return new LocalJavaCompiler(JdkTools.current().getSystemJavaCompiler());
        }

        @Override
        public Javadoc javadoc() {
            throw new UnsupportedOperationException();
        }
    }

    private static class SbtLoggerAdapter implements xsbti.Logger {
        @Override
        public void error(Supplier<String> msg) {
            LOGGER.error(msg.get());
        }

        @Override
        public void warn(Supplier<String> msg) {
            LOGGER.warn(msg.get());
        }

        @Override
        public void info(Supplier<String> msg) {
            LOGGER.info(msg.get());
        }

        @Override
        public void debug(Supplier<String> msg) {
            LOGGER.debug(msg.get());
        }

        @Override
        public void trace(Supplier<Throwable> exception) {
            LOGGER.trace(exception.get().toString());
        }
    }
}
