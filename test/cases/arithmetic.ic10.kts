import ktrapic.*
import kotlin.math.*

// Constants fold; Int division truncates and % keeps the dividend's sign.
val celsius = 22
val kelvin = celsius + 273.15
d0.Setting = kelvin
val a = d1.Setting.toInt()
val b = d2.Setting.toInt()
db.Setting = (a / b).toDouble()
db.Ratio = (a % b).toDouble()
db.Mode = sqrt(abs(d1.Setting)) * 2.0
