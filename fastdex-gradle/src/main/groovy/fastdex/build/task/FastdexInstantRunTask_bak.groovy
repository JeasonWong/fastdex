package fastdex.build.task

import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.IDevice
import fastdex.build.lib.fd.Communicator
import fastdex.build.lib.fd.ServiceCommunicator
import fastdex.build.util.*
import fastdex.build.variant.FastdexVariant
import fastdex.common.ShareConstants
import fastdex.common.fd.ProtocolConstants
import fastdex.common.utils.FileUtils
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction

/**
 * Created by tong on 17/3/12.
 */
public class FastdexInstantRunTask_bak extends DefaultTask {
    FastdexVariant fastdexVariant
    File resourceApFile
    String resDir
    boolean alreadySendPatch
    boolean alreadyExecDexTransform
    IDevice device

    FastdexInstantRunTask_bak() {
        group = 'fastdex'
    }

    private void waitForDevice(AndroidDebugBridge bridge) {
        int count = 0;
        while (!bridge.hasInitialDeviceList()) {
            try {
                Thread.sleep(100);
                count++;
            } catch (InterruptedException ignored) {
            }
            if (count > 300) {
                throw new FastdexRuntimeException("Connect adb timeout!!")
            }
        }
    }

    def preparedDevice() {
        if (device != null) {
            return
        }
        AndroidDebugBridge.initIfNeeded(false)
        AndroidDebugBridge bridge =
                AndroidDebugBridge.createBridge(FastdexUtils.getAdbCmdPath(project), false)
        waitForDevice(bridge)
        IDevice[] devices = bridge.getDevices()
        if (devices != null && devices.length > 0) {
            if (devices.length > 1) {
                throw new FastdexRuntimeException("发现了多个Android设备，请拔掉数据线，只留一个设备 V_V ")
            }
            device = devices[0]
        }

        if (device == null) {
            throw new FastdexRuntimeException("没有发现Android设备，请确认连接是否正常 adb devices")
        }
        project.logger.error("==fastdex device connected ${device.toString()}")
    }

    public void onDexTransformComplete() {
        if (!isInstantRunBuild() || !fastdexVariant.hasDexCache) {
            return
        }

        alreadyExecDexTransform = true

        preparedDevice()
        try {
            def packageName = fastdexVariant.getMergedPackageName()
            ServiceCommunicator serviceCommunicator = new ServiceCommunicator(packageName)

            MetaInfo runtimeMetaInfo = ping(device,serviceCommunicator)
            project.logger.error("==fastdex receive: ${runtimeMetaInfo}")
            if (fastdexVariant.metaInfo.buildMillis != runtimeMetaInfo.buildMillis) {
                throw new JumpException("buildMillis not equal")
            }
            if (!fastdexVariant.metaInfo.variantName.equals(runtimeMetaInfo.variantName)) {
                throw new JumpException("variantName not equal")
            }

            File resourcesApk = FastdexUtils.getResourcesApk(project,fastdexVariant.variantName)
            generateResourceApk(resourcesApk)
            File mergedPatchDex = FastdexUtils.getMergedPatchDex(fastdexVariant.project,fastdexVariant.variantName)
            File patchDex = FastdexUtils.getPatchDexFile(fastdexVariant.project,fastdexVariant.variantName)

            int changeCount = 1
            if (FileUtils.isLegalFile(mergedPatchDex)) {
                changeCount += 1
            }
            if (FileUtils.isLegalFile(patchDex)) {
                changeCount += 1
            }

            long start = System.currentTimeMillis()
            serviceCommunicator.talkToService(device, new Communicator<Boolean>() {
                @Override
                public Boolean communicate(DataInputStream input, DataOutputStream output) throws IOException {
                    output.writeInt(ProtocolConstants.MESSAGE_PATCHES)
                    output.writeLong(0L)
                    output.writeInt(changeCount)

                    project.logger.error("==fastdex write ${ShareConstants.RESOURCE_APK_FILE_NAME}")
                    output.writeUTF(ShareConstants.RESOURCE_APK_FILE_NAME)
                    byte[] bytes = FileUtils.readContents(resourcesApk)
                    output.writeInt(bytes.length)
                    output.write(bytes)
                    if (FileUtils.isLegalFile(mergedPatchDex)) {
                        project.logger.error("==fastdex write ${mergedPatchDex}")
                        output.writeUTF(ShareConstants.MERGED_PATCH_DEX)
                        bytes = FileUtils.readContents(mergedPatchDex)
                        output.writeInt(bytes.length)
                        output.write(bytes)
                    }
                    if (FileUtils.isLegalFile(patchDex)) {
                        project.logger.error("==fastdex write ${patchDex}")
                        output.writeUTF(ShareConstants.PATCH_DEX)
                        bytes = FileUtils.readContents(patchDex)
                        output.writeInt(bytes.length)
                        output.write(bytes)
                    }

                    output.writeInt(ProtocolConstants.UPDATE_MODE_WARM_SWAP)
                    output.writeBoolean(true)

                    return input.readBoolean()
                }
            })
            long end = System.currentTimeMillis();
            project.logger.error("==fastdex send patch data success. use: ${end - start}ms")

            //kill app
            killApp()
            startBootActivity()

            //project.tasks.getByName("validateSigning${fastdexVariant.variantName}").enabled = false
            project.tasks.getByName("package${fastdexVariant.variantName}").enabled = false
            project.tasks.getByName("assemble${fastdexVariant.variantName}").enabled = false
            alreadySendPatch = true
        } catch (JumpException e) {

        } catch (IOException e) {
            if (fastdexVariant.configuration.debug) {
                e.printStackTrace()
            }
        }
    }

    MetaInfo ping(IDevice device,ServiceCommunicator serviceCommunicator) {
        MetaInfo runtimeMetaInfo = serviceCommunicator.talkToService(device, new Communicator<MetaInfo>() {
            @Override
            public MetaInfo communicate(DataInputStream input, DataOutputStream output) throws IOException {
                output.writeInt(ProtocolConstants.MESSAGE_PING)
                MetaInfo runtimeMetaInfo = new MetaInfo()
                runtimeMetaInfo.active = input.readBoolean()
                runtimeMetaInfo.buildMillis = input.readLong()
                runtimeMetaInfo.variantName = input.readUTF()
                //int appPid = input.readInt()
                return runtimeMetaInfo
            }
        })

        return runtimeMetaInfo
    }

    @TaskAction
    void instantRun() {
        if (alreadySendPatch) {
            return
        }

        preparedDevice()

        //是否能ping通
        if (!alreadyExecDexTransform && fastdexVariant.hasDexCache) {
            //如果有dex缓存并且本次编译没有执行dex transform就当做资源变化处理
            //TODO 最好是对资源做下比对
            try {
                def packageName = fastdexVariant.getMergedPackageName()
                ServiceCommunicator serviceCommunicator = new ServiceCommunicator(packageName)

                MetaInfo runtimeMetaInfo = ping(device,serviceCommunicator)

                project.logger.error("==fastdex receive: ${runtimeMetaInfo}")
                if (fastdexVariant.metaInfo.buildMillis != runtimeMetaInfo.buildMillis) {
                    throw new JumpException("buildMillis not equal")
                }
                if (!fastdexVariant.metaInfo.variantName.equals(runtimeMetaInfo.variantName)) {
                    throw new JumpException("variantName not equal")
                }

                File resourcesApk = FastdexUtils.getResourcesApk(project,fastdexVariant.variantName)
                generateResourceApk(resourcesApk)

                long start = System.currentTimeMillis()

                boolean result = serviceCommunicator.talkToService(device, new Communicator<Boolean>() {
                    @Override
                    public Boolean communicate(DataInputStream input, DataOutputStream output) throws IOException {
                        output.writeInt(ProtocolConstants.MESSAGE_PATCHES)
                        output.writeLong(0L)
                        output.writeInt(1)

                        project.logger.error("==fastdex write ${ShareConstants.RESOURCE_APK_FILE_NAME}")
                        output.writeUTF(ShareConstants.RESOURCE_APK_FILE_NAME)
                        byte[] bytes = FileUtils.readContents(resourcesApk)
                        output.writeInt(bytes.length)
                        output.write(bytes)

                        output.writeInt(ProtocolConstants.UPDATE_MODE_WARM_SWAP)
                        output.writeBoolean(true)

                        input.readBoolean()

                        try {
                            return input.readBoolean()
                        } catch (Throwable e) {

                        }

                        return false
                    }
                })
                long end = System.currentTimeMillis();
                project.logger.error("==fastdex send resources.apk success. use: ${end - start}ms")

                //kill app

                if (!runtimeMetaInfo.active || !result) {
                    killApp()
                    startBootActivity()
                }
                return
            } catch (JumpException e) {

            } catch (Throwable e) {
                if (fastdexVariant.configuration.debug) {
                    e.printStackTrace()
                }
            }
        }

        normalRun(device)
    }

    void normalRun(IDevice device) {
        def targetVariant = fastdexVariant.androidVariant
        project.logger.error("==fastdex normal run ${fastdexVariant.variantName}")
        //安装app
        File apkFile = targetVariant.outputs.first().getOutputFile()
        project.logger.error("adb install -r ${apkFile}")
        device.installPackage(apkFile.absolutePath,true)
        startBootActivity()
    }

    def killApp() {
        //adb shell am force-stop 包名
        def packageName = fastdexVariant.getMergedPackageName()
        //$ adb shell kill {appPid}
        def process = new ProcessBuilder(FastdexUtils.getAdbCmdPath(project),"shell","am","force-stop","${packageName}").start()
        int status = process.waitFor()
        try {
            process.destroy()
        } catch (Throwable e) {

        }

        String cmd = "adb shell am force-stop ${packageName}"
        if (fastdexVariant.configuration.debug) {
            project.logger.error("${cmd}")
        }
        if (status != 0) {
            throw new RuntimeException("==fastdex kill app fail: \n${cmd}")
        }
    }

    def startBootActivity() {
        def packageName = fastdexVariant.getMergedPackageName()

        //启动第一个activity
        String bootActivityName = GradleUtils.getBootActivity(fastdexVariant.manifestPath)
        if (bootActivityName) {
            //$ adb shell am start -n "com.dx168.fastdex.sample/com.dx168.fastdex.sample.MainActivity" -a android.intent.action.MAIN -c android.intent.category.LAUNCHER
            def process = new ProcessBuilder(FastdexUtils.getAdbCmdPath(project),"shell","am","start","-n","\"${packageName}/${bootActivityName}\"","-a","android.intent.action.MAIN","-c","android.intent.category.LAUNCHER").start()
            int status = process.waitFor()
            try {
                process.destroy()
            } catch (Throwable e) {

            }

            String cmd = "adb shell am start -n \"${packageName}/${bootActivityName}\" -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"
            if (fastdexVariant.configuration.debug) {
                project.logger.error("${cmd}")
            }
            if (status != 0) {
                throw new RuntimeException("==fastdex start activity fail: \n${cmd}")
            }
        }
    }

    def generateResourceApk(File resourcesApk) {
        long start = System.currentTimeMillis()
        File tempDir = new File(FastdexUtils.getResourceDir(project,fastdexVariant.variantName),"temp")
        FileUtils.cleanDir(tempDir)

        File tempResourcesApk = new File(tempDir,resourcesApk.getName())
        FileUtils.copyFileUsingStream(resourceApFile,tempResourcesApk)

        File assetsPath = fastdexVariant.androidVariant.getVariantData().getScope().getMergeAssetsOutputDir()
        List<String> assetFiles = getAssetFiles(assetsPath)
        if (assetFiles.isEmpty()) {
            return
        }
        File tempAssetsPath = new File(tempDir,"assets")
        FileUtils.copyDir(assetsPath,tempAssetsPath)

        String[] cmds = new String[assetFiles.size() + 4]
        cmds[0] = FastdexUtils.getAaptCmdPath(project)
        cmds[1] = "add"
        cmds[2] = "-f"
        cmds[3] = tempResourcesApk.absolutePath
        for (int i = 0; i < assetFiles.size(); i++) {
            cmds[4 + i] = "assets/${assetFiles.get(i)}";
        }

        ProcessBuilder aaptProcess = new ProcessBuilder(cmds)
        aaptProcess.directory(tempDir)
        def process = aaptProcess.start()
        int status = process.waitFor()
        try {
            process.destroy()
        } catch (Throwable e) {

        }

        tempResourcesApk.renameTo(resourcesApk)
        def cmd = cmds.join(" ")
        if (fastdexVariant.configuration.debug) {
            project.logger.error("==fastdex add asset files into resources.apk. cmd:\n${cmd}")
        }
        if (status != 0) {
            throw new RuntimeException("==fastdex add asset files into resources.apk fail. cmd:\n${cmd}")
        }
        long end = System.currentTimeMillis();
        fastdexVariant.project.logger.error("==fastdex generate resources.apk success: \n==${resourcesApk} use: ${end - start}ms")
    }

    List<String> getAssetFiles(File dir) {
        ArrayList<String> result = new ArrayList<>()
        if (dir == null || !FileUtils.dirExists(dir.getAbsolutePath())) {
            return result
        }
        if (dir.listFiles().length == 0) {
            return result
        }
        for (File file : dir.listFiles()) {
            if (file.isFile() && !file.getName().startsWith(".")) {
                result.add(file.getName())
            }
        }
        return result;
    }

    def isInstantRunBuild() {
        String launchTaskName = project.gradle.startParameter.taskRequests.get(0).args.get(0).toString()
        boolean result = launchTaskName.endsWith("fastdex${fastdexVariant.variantName}")
        if (fastdexVariant.configuration.debug) {
            project.logger.error("==fastdex launchTaskName: ${launchTaskName}")
        }
        return result
    }
}
