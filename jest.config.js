/** @type {import('jest').Config} */
module.exports = {
  testEnvironment: 'node',
  modulePathIgnorePatterns: ['<rootDir>/lib/', '<rootDir>/example/'],
  transform: {
    '^.+\\.(ts|tsx)$': [
      'ts-jest',
      {
        tsconfig: 'tsconfig.json',
      },
    ],
  },
  moduleNameMapper: {
    '^react-native$': '<rootDir>/__mocks__/react-native.ts',
  },
};
