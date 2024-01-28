// Copyright 2020 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.sandbox;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.CommandLines.ParamFileActionInput;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.ParameterFile.ParameterFileType;
import com.google.devtools.build.lib.actions.PathMapper;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.cache.VirtualActionInput;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.exec.BinTools;
import com.google.devtools.build.lib.exec.util.FakeActionInputFileCache;
import com.google.devtools.build.lib.exec.util.SpawnBuilder;
import com.google.devtools.build.lib.sandbox.SandboxHelpers.SandboxInputs;
import com.google.devtools.build.lib.sandbox.SandboxHelpers.SandboxOutputs;
import com.google.devtools.build.lib.skyframe.TreeArtifactValue;
import com.google.devtools.build.lib.testutil.Scratch;
import com.google.devtools.build.lib.testutil.TestUtils;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import javax.annotation.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link SandboxHelpers}. */
@RunWith(JUnit4.class)
public class SandboxHelpersTest {
  private static final byte[] FAKE_DIGEST = new byte[] {1};

  private final Scratch scratch = new Scratch();
  private Path execRootPath;
  private Root execRoot;
  @Nullable private ExecutorService executorToCleanup;

  @Before
  public void createExecRoot() throws IOException {
    execRootPath = scratch.dir("/execRoot");
    execRoot = Root.fromPath(execRootPath);
  }

  @After
  public void shutdownExecutor() throws InterruptedException {
    if (executorToCleanup == null) {
      return;
    }

    executorToCleanup.shutdown();
    executorToCleanup.awaitTermination(TestUtils.WAIT_TIMEOUT_SECONDS, SECONDS);
  }

  private RootedPath execRootedPath(String execPath) {
    return RootedPath.toRootedPath(execRoot, PathFragment.create(execPath));
  }

  @Test
  public void processInputFiles_resolvesMaterializationPath_fileArtifact() throws Exception {
    ArtifactRoot outputRoot =
        ArtifactRoot.asDerivedRoot(execRootPath, ArtifactRoot.RootType.Output, "outputs");
    Path sandboxSourceRoot = scratch.dir("/faketmp/sandbox-source-roots");

    Artifact input = ActionsTestUtil.createArtifact(outputRoot, "a/a");
    FileArtifactValue symlinkTargetMetadata =
        FileArtifactValue.createForNormalFile(FAKE_DIGEST, null, 0L);
    FileArtifactValue inputMetadata =
        FileArtifactValue.createForResolvedSymlink(
            PathFragment.create("b/b"), symlinkTargetMetadata, FAKE_DIGEST);

    FakeActionInputFileCache inputMetadataProvider = new FakeActionInputFileCache();
    inputMetadataProvider.put(input, inputMetadata);

    SandboxHelpers sandboxHelpers = new SandboxHelpers();
    SandboxInputs inputs =
        sandboxHelpers.processInputFiles(
            inputMap(input),
            inputMetadataProvider,
            execRootPath,
            execRootPath,
            ImmutableList.of(),
            sandboxSourceRoot);

    assertThat(inputs.getFiles())
        .containsEntry(
            input.getExecPath(), RootedPath.toRootedPath(execRoot, PathFragment.create("b/b")));
  }

  @Test
  public void processInputFiles_resolvesMaterializationPath_treeArtifact() throws Exception {
    ArtifactRoot outputRoot =
        ArtifactRoot.asDerivedRoot(execRootPath, ArtifactRoot.RootType.Output, "outputs");
    Path sandboxSourceRoot = scratch.dir("/faketmp/sandbox-source-roots");
    SpecialArtifact parent =
        ActionsTestUtil.createTreeArtifactWithGeneratingAction(
            outputRoot, "bin/config/other_dir/subdir");

    TreeFileArtifact childA = TreeFileArtifact.createTreeOutput(parent, "a/a");
    TreeFileArtifact childB = TreeFileArtifact.createTreeOutput(parent, "b/b");
    FileArtifactValue childMetadata = FileArtifactValue.createForNormalFile(FAKE_DIGEST, null, 0L);
    TreeArtifactValue parentMetadata =
        TreeArtifactValue.newBuilder(parent)
            .putChild(childA, childMetadata)
            .putChild(childB, childMetadata)
            .setMaterializationExecPath(PathFragment.create("materialized"))
            .build();

    FakeActionInputFileCache inputMetadataProvider = new FakeActionInputFileCache();
    inputMetadataProvider.put(parent, parentMetadata.getMetadata());

    SandboxHelpers sandboxHelpers = new SandboxHelpers();
    SandboxInputs inputs =
        sandboxHelpers.processInputFiles(
            inputMap(childA, childB),
            inputMetadataProvider,
            execRootPath,
            execRootPath,
            ImmutableList.of(),
            sandboxSourceRoot);

    assertThat(inputs.getFiles())
        .containsEntry(
            childA.getExecPath(),
            RootedPath.toRootedPath(execRoot, PathFragment.create("materialized/a/a")));
    assertThat(inputs.getFiles())
        .containsEntry(
            childB.getExecPath(),
            RootedPath.toRootedPath(execRoot, PathFragment.create("materialized/b/b")));
  }

  @Test
  public void processInputFiles_materializesParamFile() throws Exception {
    SandboxHelpers sandboxHelpers = new SandboxHelpers();
    ParamFileActionInput paramFile =
        new ParamFileActionInput(
            PathFragment.create("paramFile"),
            ImmutableList.of("-a", "-b"),
            ParameterFileType.UNQUOTED,
            UTF_8);

    SandboxInputs inputs =
        sandboxHelpers.processInputFiles(
            inputMap(paramFile),
            new FakeActionInputFileCache(),
            execRootPath,
            execRootPath,
            ImmutableList.of(),
            null);

    assertThat(inputs.getFiles())
        .containsExactly(PathFragment.create("paramFile"), execRootedPath("paramFile"));
    assertThat(inputs.getSymlinks()).isEmpty();
    assertThat(FileSystemUtils.readLines(execRootPath.getChild("paramFile"), UTF_8))
        .containsExactly("-a", "-b")
        .inOrder();
    assertThat(execRootPath.getChild("paramFile").isExecutable()).isTrue();
  }

  @Test
  public void processInputFiles_materializesBinToolsFile() throws Exception {
    SandboxHelpers sandboxHelpers = new SandboxHelpers();
    BinTools.PathActionInput tool =
        new BinTools.PathActionInput(
            scratch.file("tool", "#!/bin/bash", "echo hello"),
            PathFragment.create("_bin/say_hello"));

    SandboxInputs inputs =
        sandboxHelpers.processInputFiles(
            inputMap(tool),
            new FakeActionInputFileCache(),
            execRootPath,
            execRootPath,
            ImmutableList.of(),
            null);

    assertThat(inputs.getFiles())
        .containsExactly(PathFragment.create("_bin/say_hello"), execRootedPath("_bin/say_hello"));
    assertThat(inputs.getSymlinks()).isEmpty();
    assertThat(FileSystemUtils.readLines(execRootPath.getRelative("_bin/say_hello"), UTF_8))
        .containsExactly("#!/bin/bash", "echo hello")
        .inOrder();
    assertThat(execRootPath.getRelative("_bin/say_hello").isExecutable()).isTrue();
  }

  /**
   * Test simulating a scenario when 2 parallel writes of the same virtual input both complete write
   * of the temp file and then proceed with post-processing steps one-by-one.
   */
  @Test
  public void sandboxInputMaterializeVirtualInput_parallelWritesForSameInput_writesCorrectFile()
      throws Exception {
    VirtualActionInput input = ActionsTestUtil.createVirtualActionInput("file", "hello");
    executorToCleanup = Executors.newSingleThreadExecutor();
    CyclicBarrier bothWroteTempFile = new CyclicBarrier(2);
    Semaphore finishProcessingSemaphore = new Semaphore(1);
    FileSystem customFs =
        new InMemoryFileSystem(DigestHashFunction.SHA1) {
          @Override
          @SuppressWarnings("UnsynchronizedOverridesSynchronized") // .await() inside
          protected void setExecutable(PathFragment path, boolean executable) throws IOException {
            try {
              bothWroteTempFile.await();
              finishProcessingSemaphore.acquire();
            } catch (BrokenBarrierException | InterruptedException e) {
              throw new IllegalArgumentException(e);
            }
            super.setExecutable(path, executable);
          }
        };
    Scratch customScratch = new Scratch(customFs);
    Path customExecRoot = customScratch.dir("/execroot");
    SandboxHelpers sandboxHelpers = new SandboxHelpers();

    Future<?> future =
        executorToCleanup.submit(
            () -> {
              try {
                var unused =
                    sandboxHelpers.processInputFiles(
                        inputMap(input),
                        new FakeActionInputFileCache(),
                        customExecRoot,
                        customExecRoot,
                        ImmutableList.of(),
                        null);
                finishProcessingSemaphore.release();
              } catch (IOException | InterruptedException e) {
                throw new IllegalArgumentException(e);
              }
            });
    var unused =
        sandboxHelpers.processInputFiles(
            inputMap(input),
            new FakeActionInputFileCache(),
            customExecRoot,
            customExecRoot,
            ImmutableList.of(),
            null);
    finishProcessingSemaphore.release();
    future.get();

    assertThat(customExecRoot.readdir(Symlinks.NOFOLLOW))
        .containsExactly(new Dirent("file", Dirent.Type.FILE));
    Path outputFile = customExecRoot.getChild("file");
    assertThat(FileSystemUtils.readLines(outputFile, UTF_8)).containsExactly("hello");
    assertThat(outputFile.isExecutable()).isTrue();
  }

  private static ImmutableMap<PathFragment, ActionInput> inputMap(ActionInput... inputs) {
    return Arrays.stream(inputs)
        .collect(toImmutableMap(ActionInput::getExecPath, Function.identity()));
  }

  @Test
  public void atomicallyWriteVirtualInput_writesParamFile() throws Exception {
    ParamFileActionInput paramFile =
        new ParamFileActionInput(
            PathFragment.create("paramFile"),
            ImmutableList.of("-a", "-b"),
            ParameterFileType.UNQUOTED,
            UTF_8);

    paramFile.atomicallyWriteRelativeTo(scratch.resolve("/outputs"), "-1234");

    assertThat(scratch.resolve("/outputs").readdir(Symlinks.NOFOLLOW))
        .containsExactly(new Dirent("paramFile", Dirent.Type.FILE));
    Path outputFile = scratch.resolve("/outputs/paramFile");
    assertThat(FileSystemUtils.readLines(outputFile, UTF_8)).containsExactly("-a", "-b").inOrder();
    assertThat(outputFile.isExecutable()).isTrue();
  }

  @Test
  public void atomicallyWriteVirtualInput_writesBinToolsFile() throws Exception {
    BinTools.PathActionInput tool =
        new BinTools.PathActionInput(
            scratch.file("tool", "tool_code"), PathFragment.create("tools/tool"));

    tool.atomicallyWriteRelativeTo(scratch.resolve("/outputs"), "-1234");

    assertThat(scratch.resolve("/outputs").readdir(Symlinks.NOFOLLOW))
        .containsExactly(new Dirent("tools", Dirent.Type.DIRECTORY));
    Path outputFile = scratch.resolve("/outputs/tools/tool");
    assertThat(FileSystemUtils.readLines(outputFile, UTF_8)).containsExactly("tool_code");
    assertThat(outputFile.isExecutable()).isTrue();
  }

  @Test
  public void atomicallyWriteVirtualInput_writesArbitraryVirtualInput() throws Exception {
    VirtualActionInput input = ActionsTestUtil.createVirtualActionInput("file", "hello");

    // Store an existing directory at the location where atomicallyWriteTo()
    // writes its temporary file. It should be removed prior to the creation of
    // the temporary file.
    scratch.resolve("/outputs/file-1234").createDirectoryAndParents();

    input.atomicallyWriteRelativeTo(scratch.resolve("/outputs"), "-1234");

    assertThat(scratch.resolve("/outputs").readdir(Symlinks.NOFOLLOW))
        .containsExactly(new Dirent("file", Dirent.Type.FILE));
    Path outputFile = scratch.resolve("/outputs/file");
    assertThat(FileSystemUtils.readLines(outputFile, UTF_8)).containsExactly("hello");
    assertThat(outputFile.isExecutable()).isTrue();
  }

  @Test
  public void cleanExisting_updatesDirs() throws IOException, InterruptedException {
    RootedPath inputTxt =
        RootedPath.toRootedPath(
            Root.fromPath(scratch.getFileSystem().getPath("/")), PathFragment.create("hello.txt"));
    Path rootDir = execRootPath.getParentDirectory();
    PathFragment input1 = PathFragment.create("existing/directory/with/input1.txt");
    PathFragment input2 = PathFragment.create("partial/directory/input2.txt");
    PathFragment input3 = PathFragment.create("new/directory/input3.txt");
    SandboxInputs inputs =
        new SandboxInputs(
            ImmutableMap.of(input1, inputTxt, input2, inputTxt, input3, inputTxt),
            ImmutableMap.of(),
            ImmutableMap.of(),
            ImmutableMap.of());
    Set<PathFragment> inputsToCreate = new LinkedHashSet<>();
    LinkedHashSet<PathFragment> dirsToCreate = new LinkedHashSet<>();
    SandboxHelpers.populateInputsAndDirsToCreate(
        ImmutableSet.of(),
        inputsToCreate,
        dirsToCreate,
        Iterables.concat(
            ImmutableSet.of(), inputs.getFiles().keySet(), inputs.getSymlinks().keySet()),
        SandboxOutputs.create(
            ImmutableSet.of(PathFragment.create("out/dir/output.txt")), ImmutableSet.of()));

    PathFragment inputDir1 = input1.getParentDirectory();
    PathFragment inputDir2 = input2.getParentDirectory();
    PathFragment inputDir3 = input3.getParentDirectory();
    PathFragment outputDir = PathFragment.create("out/dir");
    assertThat(dirsToCreate).containsExactly(inputDir1, inputDir2, inputDir3, outputDir);
    assertThat(inputsToCreate).containsExactly(input1, input2, input3);

    // inputdir1 exists fully
    execRootPath.getRelative(inputDir1).createDirectoryAndParents();
    // inputdir2 exists partially, should be kept nonetheless.
    execRootPath
        .getRelative(inputDir2)
        .getParentDirectory()
        .getRelative("doomedSubdir")
        .createDirectoryAndParents();
    // inputDir3 just doesn't exist
    // outputDir only exists partially
    execRootPath.getRelative(outputDir).getParentDirectory().createDirectoryAndParents();
    execRootPath.getRelative("justSomeDir/thatIsDoomed").createDirectoryAndParents();
    // `thiswillbeafile/output` simulates a directory that was in the stashed dir but whose same
    // path is used later for a regular file.
    scratch.dir("/execRoot/thiswillbeafile/output");
    scratch.file("/execRoot/thiswillbeafile/output/file1");
    dirsToCreate.add(PathFragment.create("thiswillbeafile"));
    PathFragment input4 = PathFragment.create("thiswillbeafile/output");
    SandboxInputs inputs2 =
        new SandboxInputs(
            ImmutableMap.of(input1, inputTxt, input2, inputTxt, input3, inputTxt, input4, inputTxt),
            ImmutableMap.of(),
            ImmutableMap.of(),
            ImmutableMap.of());
    SandboxHelpers.cleanExisting(rootDir, inputs2, inputsToCreate, dirsToCreate, execRootPath);
    assertThat(dirsToCreate).containsExactly(inputDir2, inputDir3, outputDir);
    assertThat(execRootPath.getRelative("existing/directory/with").exists()).isTrue();
    assertThat(execRootPath.getRelative("partial").exists()).isTrue();
    assertThat(execRootPath.getRelative("partial/doomedSubdir").exists()).isFalse();
    assertThat(execRootPath.getRelative("partial/directory").exists()).isFalse();
    assertThat(execRootPath.getRelative("justSomeDir/thatIsDoomed").exists()).isFalse();
    assertThat(execRootPath.getRelative("out").exists()).isTrue();
    assertThat(execRootPath.getRelative("out/dir").exists()).isFalse();
  }

  @Test
  public void populateInputsAndDirsToCreate_createsMappedDirectories() {
    ArtifactRoot outputRoot =
        ArtifactRoot.asDerivedRoot(execRootPath, ArtifactRoot.RootType.Output, "outputs");
    ActionInput outputFile = ActionsTestUtil.createArtifact(outputRoot, "bin/config/dir/file");
    ActionInput outputDir =
        ActionsTestUtil.createTreeArtifactWithGeneratingAction(
            outputRoot, "bin/config/other_dir/subdir");
    PathMapper pathMapper =
        execPath -> PathFragment.create(execPath.getPathString().replace("config/", ""));
    Spawn spawn =
        new SpawnBuilder().withOutputs(outputFile, outputDir).setPathMapper(pathMapper).build();
    var sandboxHelpers = new SandboxHelpers();
    LinkedHashSet<PathFragment> writableDirs = new LinkedHashSet<>();
    LinkedHashSet<PathFragment> inputsToCreate = new LinkedHashSet<>();
    LinkedHashSet<PathFragment> dirsToCreate = new LinkedHashSet<>();

    SandboxHelpers.populateInputsAndDirsToCreate(
        writableDirs,
        inputsToCreate,
        dirsToCreate,
        ImmutableList.of(),
        sandboxHelpers.getOutputs(spawn));

    assertThat(writableDirs).isEmpty();
    assertThat(inputsToCreate).isEmpty();
    assertThat(dirsToCreate)
        .containsExactly(
            PathFragment.create("outputs/bin/dir"),
            PathFragment.create("outputs/bin/other_dir/subdir"));
  }

  @Test
  public void moveOutputs_mappedPathMovedToUnmappedPath() throws Exception {
    PathFragment unmappedOutputPath = PathFragment.create("bin/config/output");
    PathMapper pathMapper =
        execPath -> PathFragment.create(execPath.getPathString().replace("config/", ""));
    Spawn spawn =
        new SpawnBuilder()
            .withOutputs(unmappedOutputPath.getPathString())
            .setPathMapper(pathMapper)
            .build();
    var sandboxHelpers = new SandboxHelpers();
    Path sandboxBase = execRootPath.getRelative("sandbox");
    PathFragment mappedOutputPath = PathFragment.create("bin/output");
    sandboxBase.getRelative(mappedOutputPath).getParentDirectory().createDirectoryAndParents();
    FileSystemUtils.writeLinesAs(
        sandboxBase.getRelative(mappedOutputPath), UTF_8, "hello", "pathmapper");

    Path realBase = execRootPath.getRelative("real");
    SandboxHelpers.moveOutputs(sandboxHelpers.getOutputs(spawn), sandboxBase, realBase);

    assertThat(
            FileSystemUtils.readLines(
                realBase.getRelative(unmappedOutputPath.getPathString()), UTF_8))
        .containsExactly("hello", "pathmapper")
        .inOrder();
    assertThat(sandboxBase.getRelative(mappedOutputPath).exists()).isFalse();
  }
}
