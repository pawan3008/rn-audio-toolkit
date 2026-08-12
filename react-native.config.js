module.exports = {
  dependency: {
    platforms: {
      android: {
        packageImportPath: 'import com.audiokit.AudioKitPackage;',
        packageInstance: 'new AudioKitPackage()',
      },
      ios: {},
    },
  },
};
