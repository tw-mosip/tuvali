import Foundation

@available(iOS 13.0, *)
@objc(VersionModule)
class VersionModule: RCTEventEmitter {
    var tuvaliVersion: String = "unknown"
    
    @objc func setTuvaliVersion(_ version: String) -> String{
        tuvaliVersion = version
        os_log("Tuvali version - %{public}@",tuvaliVersion);
        return tuvaliVersion
    }
}
