// Deploy built rpk to running Vela emulator (xiaomi_band) or real device.
// Usage: node scripts/deploy.js [serial]
const { execSync } = require("child_process")
const fs = require("fs")
const path = require("path")

const PKG = "com.xiaomi.xms.wearable.demo"
const SERIAL = process.argv[2] || "emulator-5554"
const DIST = path.join(__dirname, "..", "dist")
const APP_DIR = "/data/quickapp/app"

function sh(cmd) {
  console.log("$", cmd)
  try {
    const out = execSync(cmd, { stdio: "pipe", encoding: "utf8" })
    if (out.trim()) console.log(out.trim())
    return out
  } catch (e) {
    const msg = (e.stdout || "") + (e.stderr || "")
    if (msg.includes("file pushed")) {
      console.log(msg.trim())
      return msg
    }
    console.error(msg.trim())
    throw e
  }
}

const rpk = fs.readdirSync(DIST).find(f => f.startsWith(PKG) && f.endsWith(".rpk"))
if (!rpk) {
  console.error(`No rpk found in ${DIST}. Run "npm run build" first.`)
  process.exit(1)
}
const rpkPath = path.join(DIST, rpk).replace(/\\/g, "/")
const remoteRpk = `${APP_DIR}/${PKG}.rpk`
const remoteDir = `${APP_DIR}/${PKG}`

sh(`adb -s ${SERIAL} push "${rpkPath}" ${remoteRpk}`)
try { sh(`adb -s ${SERIAL} shell rm -r ${remoteDir}`) } catch (_) {}
sh(`adb -s ${SERIAL} shell mkdir ${remoteDir}`)
sh(`adb -s ${SERIAL} shell unzip -o ${remoteRpk} -d ${remoteDir}`)
try { sh(`adb -s ${SERIAL} shell killall vapp`) } catch (_) {}
sh(`adb -s ${SERIAL} shell "vapp app/${PKG} &"`)

console.log("\n[deploy] ok — app launched on", SERIAL)
