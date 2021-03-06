package com.simplicityitself.vulcan.provision.vm.vagrant

import com.simplicityitself.vulcan.VirtualMachineImage
import com.simplicityitself.vulcan.provision.vm.VirtualMachineProvisioner
import groovy.util.logging.Slf4j
import com.simplicityitself.vulcan.VirtualMachine


@Slf4j
class VagrantVirtualMachineProvision implements VirtualMachineProvisioner {

  File vagrantBaseDir
  File specificationDir
  File vagrantKey

  String IP_ROOT="192.168.18."
  int octet = 10
  private List<VirtualMachine> virtualMachines = []
  Boolean osIsWindows
  String vagrantBatDir
  private String command

  //ToDo DaCo...remove. Temp for test purposes
  VagrantVirtualMachineProvision(){}

  VagrantVirtualMachineProvision(String specificationName) {
    determineOS()
    setupVagrantStorage()
    setupVagrantKey()
    setupSpecificationDir(specificationName)
    downloadBaseBox("vulcan", "http://dl.dropbox.com/u/1537815/precise64.box")
    downloadBaseBox("lucid64", "http://files.vagrantup.com/lucid64.box")
    ensureVagrantWorks()
  }

  def determineOS() {
    def ant = new AntBuilder()
    ant.condition(property:"winOS"){
      os(family:"windows")
    }
    osIsWindows = ant.project.getProperties().get("winOS") != null
  }

  void setupVagrantStorage() {
    File baseDir = new File(".")

    if (!vagrantBaseDir) {
      vagrantBaseDir = new File("${baseDir.absolutePath}/vagrant-temp")
      log.info "Vagrant resources at $vagrantBaseDir"
    }
  }

  def void setupVagrantKey() {
    def privateKeyValue = getClass().getResourceAsStream("/vagrant.key")?.text
    if (!privateKeyValue) {
      def f = new File("src/resources/vagrant.key")
      if (f.exists()) {
        privateKeyValue = f.text
      }
    }
    vagrantKey = File.createTempFile("vagrant", "insecurePrivateKey")
    vagrantKey << privateKeyValue
  }

  def setupSpecificationDir(String specificationName) {
    specificationDir = new File("${vagrantBaseDir}/${specificationName}")
    specificationDir.mkdirs()
  }

  def downloadBaseBox(baseBoxId, baseBoxLocation) {
    AntBuilder ant = new AntBuilder()
    if (!isBaseBoxInstalled(baseBoxId)) {
      log.info "Downloading basebox ${baseBoxId} from location ${baseBoxLocation}"
      runVagrantCommand("box add ${baseBoxId} ${baseBoxLocation}", ant)
    } else {
      log.info "Basebox ${baseBoxId} already installed"
    }
  }

  def boolean isBaseBoxInstalled(baseBoxId) {
    AntBuilder ant = new AntBuilder()
    runVagrantCommand("box list", ant)
    String boxes =  ant.project.properties.cmdOut
    def listOfBoxes = boxes.tokenize()
    listOfBoxes.contains(baseBoxId)
  }

  @Override
  VirtualMachineImage findImageWithTags(String... tags) {
    return null  //To change body of implemented methods use File | Settings | File Templates.
  }

  void provision(List <VirtualMachine> virtualMachines) {
    if (virtualMachines) {
      this.virtualMachines = virtualMachines
      log.info("Creating vagrant environment at ${specificationDir.absolutePath}")
      createConfig(virtualMachines)

      startEnvironment()

      virtualMachines.each {
        it.executeCommandAndReturn("echo ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQDGtfH+jJOa1kLxc/WGX3+q18f+fTAuMbDRsSNtWMq+gih8BDuWpxYPBJeyg6bfVWf7XldYpFR+qrqmPglWYaSX+rIS8lyhjTyWULgRkicAQ8BSJlqr2FzuLFnYlkf3jDNPyR54dfex3W6nPniNjJQbrNJdZM49Q30baQJ+BaG/NAK9DAeoiCI2Ni0o8XJSinTv68uW7hMF/qe867TDWyDujjekZ+TOgNsFoi9HtzR563nDyIhb0YAG7Tz1Mwrk8uoR0jQfR3dhytJGo8KzmktjNVvtqsLdkWSaY9ScisYe+mRCP5xKGFi31mNLYnLvMqJqqbwqR08ABamNJBGhKA+h david.dawson@dawsonsystems.com >> /home/vagrant/.ssh/authorized_keys")
      }
    }
  }

  void disconnect() {
    if (System.properties["keeprunning"] != null) {
      log.info "Keeping running by user request"
      return
    }

    runVagrantCommand("halt -f")
  }

  @Override
  boolean isRunning(VirtualMachine vm) {
    def ant = new AntBuilder()
    runVagrantCommand("status ${vm.name}", ant)
    ant.project.properties.cmdOut.contains "running"
  }

  @Override
  void deleteFromProvider(VirtualMachine vm) {
    def ant = new AntBuilder()

    ant.exec(executable:"vagrant", dir:specificationDir, failonerror:true) {
      arg(line:"halt -f ${vm.name}")
    }
  }

  @Override
  VirtualMachineImage generateProviderImageFrom(String imageName, VirtualMachine vm) {
    //TODO, handle images properly
    return new VirtualMachineImage(id: vm.identifier, tags:[])
  }

  @Override
  void deleteFromProvider(VirtualMachineImage virtualMachineImage) {
    //TODO, handle images properly

  }

  @Override
  VirtualMachineImage getImage(String id) {
    //TODO, handle images properly
    return new VirtualMachineImage(id:id)
  }

  @Override
  VirtualMachineImage addTagToImage(VirtualMachineImage image, String tag, String value) {
    //TODO, handle images properly
    image.tags << tag
    return image
  }

  String configForVm(VirtualMachine virtualMachine) {
    //
    """
    config.vm.define :${virtualMachine.name} do |sub_config|
      sub_config.vm.box = "lucid64"
      sub_config.vm.network :hostonly, "${virtualMachine.ipAddressToConnectTo}"
      sub_config.vm.host_name = "${virtualMachine.name}"
      sub_config.vm.customize ["modifyvm", :id, "--memory", ${virtualMachine.memory}]
    end
    """
  }

  private String createConfig(List<VirtualMachine> virtualMachines) {

    def vagrantConfig = new File(specificationDir, "Vagrantfile")
    if (!vagrantConfig.delete()) {
      println "Couldn't delete it!"
    }

    def ret = """
Vagrant::Config.run do |config|
    config.ssh.guest_port = 22
    config.ssh.username="vagrant"

    """

    virtualMachines.each { VirtualMachine vm ->
      String ip = "${IP_ROOT}${octet++}"
      vm.delegate = new VagrantVirtualMachineDelegate(privateKey: vagrantKey, ipAddress: ip)
      ret += configForVm(vm)
    }

    ret +="""
end
    """

    vagrantConfig << ret
  }

  def startEnvironment() {
    runVagrantCommand("destroy -f")
    runVagrantCommand("up")
  }

  private void runVagrantCommand(command) {
    AntBuilder ant = new AntBuilder()
    runVagrantCommand(command, ant)
  }

  private void runVagrantCommand(command, ant) throws IllegalStateException {
    if (osIsWindows) {
      ant.exec(outputproperty:"cmdOut",
          errorproperty: "cmdErr",
          resultproperty:"cmdExit",
          executable: "cmd",
          dir: specificationDir,
          failonerror: false) {
        arg(value: "/c")
        arg(path: vagrantBatDir)
        arg(line: command)
      }
    } else {
      ant.exec(executable: "vagrant", dir: specificationDir, failonerror: false, resultproperty:"cmdExit") {
        arg(line: command)
      }
    }
  }

  void ensureVagrantWorks() {
    runVagrantCommand("-v")
    log.info "Vagrant is installed and functional"
  }

  String getVagrantBatDir() {
    if (vagrantBatDir == null) {
      def env = System.getenv()
      def paths = env['Path'].tokenize(';')
      File tempFile
      for (String path : paths) {
        tempFile = new File(path + "\\vagrant.bat")
        if (tempFile.exists()) {
          vagrantBatDir = tempFile.getAbsolutePath()
          break
        }
      }
    }
    vagrantBatDir
  }

  /**
   * TODO, generify.
   *
   * This adds a closure that is executed to bring the VM into a certain state, intended to be active
   * for local or test environments that need to be matched with a production state.
   * Should be made generic and have a better selection mechanism for when to be fired.
   */
  void addFixtureForVm(VirtualMachine vm, Closure fixture) {
    if (virtualMachines.contains(vm)) {
      log.info ("Fixture passed for provision against VM ${vm.name}, running against VM")
      fixture(vm)
    } else {
      log.info ("Fixture passed for provision against VM ${vm.name}, this vm is not being handled by vagrant")
    }
  }
}
