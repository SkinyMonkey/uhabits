import SwiftUI
import class UhabitsCore.PaletteColor

struct NewHabitView: View {
    @EnvironmentObject var store: HabitStore
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var colorIndex = 8

    var body: some View {
        NavigationStack {
            Form {
                TextField("Name", text: $name)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack {
                        ForEach(0..<20, id: \.self) { i in
                            Circle()
                                .fill(Color(hex: PaletteColor(paletteIndex: Int32(i)).toCsvColor()))
                                .frame(width: 32, height: 32)
                                .overlay { if i == colorIndex { Image(systemName: "checkmark").foregroundStyle(.white) } }
                                .onTapGesture { colorIndex = i }
                        }
                    }
                }
            }
            .navigationTitle("New habit")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        store.create(name: name.trimmingCharacters(in: .whitespaces), colorIndex: colorIndex)
                        dismiss()
                    }
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
    }
}
