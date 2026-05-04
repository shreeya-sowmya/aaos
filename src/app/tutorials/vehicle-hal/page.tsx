export default function VehicleHalPage() {
  return (
    <div className="mdx-content max-w-3xl">
      <h1>Custom Vehicle HAL</h1>
      <p>
        The Vehicle Hardware Abstraction Layer (VHAL) is the bridge between Android Automotive OS
        and the vehicle hardware. This tutorial walks through implementing a custom VHAL property in C++.
      </p>

      <h2>Prerequisites</h2>
      <ul>
        <li>AAOS source checked out (<code>repo sync</code>)</li>
        <li><code>adb</code> connected to your emulator or target device</li>
        <li>Familiarity with C++17</li>
      </ul>

      <h2>Defining a property</h2>
      <pre><code>{`// Custom seat heater intensity property
constexpr int32_t SEAT_HEATER_INTENSITY =
    0x15400503 | VehiclePropertyGroup::VENDOR |
    VehicleArea::SEAT |
    VehiclePropertyType::INT32;`}</code></pre>

      <h2>Implementing the getter</h2>
      <pre><code>{`StatusCode DefaultVehicleHal::getValue(
    const VehiclePropValue& requestedPropValue,
    VehiclePropValue* outValue) {

  if (requestedPropValue.prop == SEAT_HEATER_INTENSITY) {
    outValue->value.int32Values = {mSeatHeaterLevel};
    return StatusCode::OK;
  }
  return StatusCode::INVALID_ARG;
}`}</code></pre>

      <h2>Kotlin — subscribing from the app layer</h2>
      <pre><code>{`val carPropertyManager =
    car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager

carPropertyManager.registerCallback(
    object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val intensity = value.value as Int
            updateHeaterUI(intensity)
        }
        override fun onErrorEvent(propId: Int, zone: Int) { }
    },
    SEAT_HEATER_INTENSITY,
    CarPropertyManager.SENSOR_RATE_NORMAL
)`}</code></pre>

      <h2>Testing with adb</h2>
      <pre><code>{`adb shell cmd car_service inject-vhal-event \\
  0x15400503 0 1`}</code></pre>
    </div>
  )
}
