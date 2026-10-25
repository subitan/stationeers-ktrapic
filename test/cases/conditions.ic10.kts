import ktrapic.*

// A thermostat: heat below 290 K, cool above 300 K, with the housing's light showing which.
while (true) {
  val temperature = d0.Temperature
  val heating = temperature < 290.0
  val cooling = temperature > 300.0
  d1.On = heating
  d2.On = cooling
  db.Color = if (heating) Color.Red else if (cooling) Color.Blue else Color.Green
  db.On = heating || cooling
  yield()
}
